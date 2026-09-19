package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteDecision;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.application.journey.port.out.RerouteEligibilityPolicy;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 요청 진입점이 <b>외부 검색을 언제 내보내는지</b>.
 *
 * <p>이 시험의 무게중심은 "안 부르는 경우"에 있다. 적격성 판정이 거절했는데 공급자가 한 번이라도
 * 불리면 버튼 연타가 곧 외부 API 연타가 되고, 사다리 순서가 뒤집히면 같은 정류장의 다른 노선으로
 * 끝날 일에도 매번 경로를 통째로 다시 검색한다.
 */
@DisplayName("재탐색 제안 요청")
class RerouteProposalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final Long USER_ID = 7L;
    private static final Long JOURNEY_ID = 42L;

    private final Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final RerouteProposalTxService txService = mock(RerouteProposalTxService.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();
    private final JourneyRerouteMetrics metrics =
            new JourneyRerouteMetrics(new SimpleMeterRegistry());

    /** 호출된 순서를 그대로 적는다. mock 의 verify 로는 "어느 쪽이 먼저"가 읽기 어렵다. */
    private final List<RerouteProposalKind> callOrder = new ArrayList<>();

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.of(journey()));
        when(txService.store(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> Optional.of(view(invocation.getArgument(1))));
    }

    // ── 외부 검색이 나가지 않는 경우 ────────────────────────────────────────

    @Test
    @DisplayName("적격하지 않으면 공급자를 한 번도 부르지 않는다")
    void ineligibleRequestNeverCallsProviders() {
        RerouteCandidateProvider provider = recordingProvider(
                RerouteProposalKind.ROUTE_OPTION, 0, List.of(candidate()));
        RerouteProposalService service = service(denying("cooldown"), List.of(provider));

        assertThatThrownBy(() -> service.request(USER_ID, JOURNEY_ID, 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", JourneyErrorCode.REROUTE_NOT_ELIGIBLE);

        assertThat(callOrder)
                .as("버튼 연타가 외부 검색 연타가 되면 안 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("기본 상태(트리거 없음)에서는 항상 거절이다")
    void defaultPolicyRejects() {
        RerouteProposalService service = service(
                (journey, transferIndex, now) -> Optional.of("disabled"), List.of());

        assertThatThrownBy(() -> service.request(USER_ID, JOURNEY_ID, 0))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 적격성 판정도 묻지 않는다")
    void disabledFeatureRejectsBeforePolicy() {
        properties.setEnabled(false);
        RerouteEligibilityPolicy policy = mock(RerouteEligibilityPolicy.class);
        RerouteProposalService service = service(policy, List.of());

        assertThatThrownBy(() -> service.request(USER_ID, JOURNEY_ID, 0))
                .isInstanceOf(BusinessException.class);

        verify(policy, never()).denialReason(any(), anyInt(), any());
    }

    @Test
    @DisplayName("남의 여정이면 거절한다")
    void foreignJourneyIsRejected() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .thenReturn(Optional.empty());
        RerouteProposalService service = service(allowing(), List.of());

        assertThatThrownBy(() -> service.request(USER_ID, JOURNEY_ID, 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", JourneyErrorCode.JOURNEY_NOT_FOUND);
    }

    // ── 사다리 순서 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("싼 것부터 본다 — 노선 후보가 나오면 경로 재검색은 부르지 않는다")
    void cheaperProviderWins() {
        RerouteProposalService service = service(allowing(), List.of(
                recordingProvider(RerouteProposalKind.REMAINING_PATH, 1, List.of(candidate())),
                recordingProvider(RerouteProposalKind.ROUTE_OPTION, 0, List.of(candidate()))));

        Optional<RerouteProposalView> result = service.request(USER_ID, JOURNEY_ID, 0);

        assertThat(callOrder)
                .as("순서를 뒤집으면 요금이 폭발한다")
                .containsExactly(RerouteProposalKind.ROUTE_OPTION);
        assertThat(result).get()
                .satisfies(view -> assertThat(view.kind()).isEqualTo(RerouteProposalKind.ROUTE_OPTION));
    }

    @Test
    @DisplayName("앞선 공급자가 빈손이면 다음으로 넘어간다")
    void emptyProviderFallsThrough() {
        RerouteProposalService service = service(allowing(), List.of(
                recordingProvider(RerouteProposalKind.ROUTE_OPTION, 0, List.of()),
                recordingProvider(RerouteProposalKind.REMAINING_PATH, 1, List.of(candidate()))));

        Optional<RerouteProposalView> result = service.request(USER_ID, JOURNEY_ID, 0);

        assertThat(callOrder).containsExactly(
                RerouteProposalKind.ROUTE_OPTION, RerouteProposalKind.REMAINING_PATH);
        assertThat(result).isPresent();
    }

    // ── 빈손·실패 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("모두 빈손이면 저장하지 않고 빈 결과다 — 예외가 아니다")
    void allEmptyReturnsEmptyWithoutStoring() {
        RerouteProposalService service = service(allowing(), List.of(
                recordingProvider(RerouteProposalKind.ROUTE_OPTION, 0, List.of())));

        Optional<RerouteProposalView> result = service.request(USER_ID, JOURNEY_ID, 0);

        assertThat(result)
                .as("'찾아봤지만 나은 것이 없다'는 정상 결과다. 오류로 만들면 앱이 재시도를 돈다")
                .isEmpty();
        verify(txService, never()).store(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("저장이 빈 결과면 다음 공급자로 넘어가지 않는다")
    void staleContextStopsInsteadOfFallingThrough() {
        when(txService.store(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        RerouteProposalService service = service(allowing(), List.of(
                recordingProvider(RerouteProposalKind.ROUTE_OPTION, 0, List.of(candidate())),
                recordingProvider(RerouteProposalKind.REMAINING_PATH, 1, List.of(candidate()))));

        Optional<RerouteProposalView> result = service.request(USER_ID, JOURNEY_ID, 0);

        assertThat(result).isEmpty();
        assertThat(callOrder)
                .as("여정이 움직인 것이지 후보가 없던 것이 아니다 — 더 비싼 검색을 부를 이유가 없다")
                .containsExactly(RerouteProposalKind.ROUTE_OPTION);
    }

    @Test
    @DisplayName("공급자가 터져도 요청 전체가 죽지 않는다")
    void providerFailureDoesNotFailRequest() {
        RerouteCandidateProvider exploding = new RerouteCandidateProvider() {
            @Override public RerouteProposalKind kind() { return RerouteProposalKind.ROUTE_OPTION; }
            @Override public int priority() { return 0; }
            @Override public List<RerouteCandidate> findCandidates(RerouteContext context) {
                throw new IllegalStateException("ODsay 500");
            }
        };
        RerouteProposalService service = service(allowing(), List.of(exploding,
                recordingProvider(RerouteProposalKind.REMAINING_PATH, 1, List.of(candidate()))));

        assertThat(service.request(USER_ID, JOURNEY_ID, 0))
                .as("재탐색 실패가 여정을 흔들면 안 된다")
                .isPresent();
    }

    // ── 신선도 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("스냅샷 시각은 가장 낡은 근거를 따른다")
    void snapshotFollowsOldestObservation() {
        RerouteProposalService service = service(allowing(), List.of(recordingProvider(
                RerouteProposalKind.ROUTE_OPTION, 0,
                List.of(candidate(NOW.minusSeconds(10)), candidate(NOW.minusSeconds(90))))));

        service.request(USER_ID, JOURNEY_ID, 0);

        verify(txService).store(any(), any(), any(),
                org.mockito.ArgumentMatchers.eq(NOW.minusSeconds(90)),
                org.mockito.ArgumentMatchers.eq(NOW.plus(properties.getProposalTtl())));
    }

    // ── 조회 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("조회는 만료 전이를 먼저 돌린다")
    void findActiveResolvesStaleFirst() {
        RerouteProposalService service = service(allowing(), List.of());
        when(txService.findActive(JOURNEY_ID)).thenReturn(Optional.empty());

        service.findActive(USER_ID, JOURNEY_ID);

        verify(txService).resolveStale(JOURNEY_ID, 1, NOW);
    }

    @Test
    @DisplayName("기능이 꺼져 있으면 조회는 아무것도 건드리지 않는다")
    void findActiveIsInertWhenDisabled() {
        properties.setEnabled(false);
        RerouteProposalService service = service(allowing(), List.of());

        assertThat(service.findActive(USER_ID, JOURNEY_ID)).isEmpty();

        verify(txService, never()).resolveStale(anyLong(), anyInt(), any());
    }

    // ── 조회 시 검증(PR-3) ──────────────────────────────────────────────────

    @Test
    @DisplayName("지나간 하차점의 후보는 목록에서 뺀다")
    void passedAlightStopsAreDropped() {
        RerouteProposalService service = service(allowing(), List.of());
        RerouteCandidate passed = candidateAlighting(NOW.plusSeconds(30));
        RerouteCandidate alive = candidateAlighting(NOW.plusSeconds(600));
        when(txService.findActive(JOURNEY_ID)).thenReturn(Optional.of(
                new RerouteProposalView(1L, JOURNEY_ID, 1, origin(),
                        RerouteProposalKind.REMAINING_PATH, 1L, NOW, NOW.plusSeconds(300),
                        List.of(passed, alive))));

        Optional<RerouteProposalView> result = service.findActive(USER_ID, JOURNEY_ID);

        assertThat(result).isPresent();
        assertThat(result.get().candidates())
                .as("리드타임(90초)에 못 미치는 하차점은 이미 못 내린다")
                .containsExactly(alive);
        verify(txService, never()).expireValidatedOut(anyLong(), any());
    }

    @Test
    @DisplayName("전부 지나갔으면 제안 자체를 접는다 — 빈 목록이 자리를 막으면 안 된다")
    void allPassedExpiresTheProposal() {
        RerouteProposalService service = service(allowing(), List.of());
        when(txService.findActive(JOURNEY_ID)).thenReturn(Optional.of(
                new RerouteProposalView(1L, JOURNEY_ID, 1, origin(),
                        RerouteProposalKind.REMAINING_PATH, 1L, NOW, NOW.plusSeconds(300),
                        List.of(candidateAlighting(NOW.plusSeconds(30))))));

        assertThat(service.findActive(USER_ID, JOURNEY_ID)).isEmpty();
        verify(txService).expireValidatedOut(1L, NOW);
    }

    @Test
    @DisplayName("하차 정보가 없는 옛 후보는 그대로 통과한다")
    void legacyCandidatesWithoutAlightSurvive() {
        RerouteProposalService service = service(allowing(), List.of());
        when(txService.findActive(JOURNEY_ID)).thenReturn(Optional.of(view(
                RerouteProposalKind.ROUTE_OPTION)));

        assertThat(service.findActive(USER_ID, JOURNEY_ID)).isPresent();
        verify(txService, never()).expireValidatedOut(anyLong(), any());
    }

    // ── 거절·무시(PR-3) ─────────────────────────────────────────────────────

    @Test
    @DisplayName("기능이 꺼져 있어도 거절은 받는다 — 사용자 손에는 이미 알림이 있다")
    void decisionIsAcceptedEvenWhenDisabled() {
        properties.setEnabled(false);
        RerouteProposalService service = service(allowing(), List.of());
        when(txService.close(JOURNEY_ID, 1L, RerouteProposalStatus.REJECTED, false, NOW))
                .thenReturn(new RerouteDecision(true, true, false));

        RerouteDecision decision = service.decide(USER_ID, JOURNEY_ID, 1L,
                RerouteProposalStatus.REJECTED, false);

        assertThat(decision.closed()).isTrue();
        assertThat(decision.terminated()).isTrue();
    }

    @Test
    @DisplayName("남의 여정은 거절도 못 한다")
    void decisionRequiresOwnership() {
        when(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, 99L))
                .thenReturn(Optional.empty());
        RerouteProposalService service = service(allowing(), List.of());

        assertThatThrownBy(() -> service.decide(99L, JOURNEY_ID, 1L,
                RerouteProposalStatus.DISMISSED, false))
                .isInstanceOf(BusinessException.class);
        verify(txService, never()).close(any(), any(), any(), anyBoolean(), any());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteCandidate candidateAlighting(Instant alightAt) {
        return new RerouteCandidate("c-" + alightAt, RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(1, null),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_200), 1_200, 600, 1, 300,
                        "REALTIME", "GOOD", NOW, List.of("370 버스"),
                        new RerouteCandidateDisplay.AlightStop("ST-B", "정류장B", alightAt, false)));
    }

    private RerouteProposalService service(RerouteEligibilityPolicy policy,
                                           List<RerouteCandidateProvider> providers) {
        return new RerouteProposalService(sessionRepository, txService, policy, providers,
                properties, metrics, clock);
    }

    private RerouteEligibilityPolicy allowing() {
        return (journey, transferIndex, now) -> Optional.empty();
    }

    private RerouteEligibilityPolicy denying(String reason) {
        return (journey, transferIndex, now) -> Optional.of(reason);
    }

    private RerouteCandidateProvider recordingProvider(RerouteProposalKind kind, int priority,
                                                       List<RerouteCandidate> candidates) {
        return new RerouteCandidateProvider() {
            @Override public RerouteProposalKind kind() { return kind; }
            @Override public int priority() { return priority; }
            @Override public List<RerouteCandidate> findCandidates(RerouteContext context) {
                callOrder.add(kind);
                return candidates;
            }
        };
    }

    private RerouteCandidate candidate() {
        return candidate(NOW.minusSeconds(20));
    }

    private RerouteCandidate candidate(Instant observedAt) {
        return new RerouteCandidate(null, RerouteProposalKind.ROUTE_OPTION,
                new RerouteCandidateApply.RouteOptionApply(1, 0, "BL-370", "UP"),
                new RerouteCandidateDisplay(NOW.plusSeconds(1_200), 1_200, 120, 1, 300,
                        "REALTIME", "HIGH", observedAt, List.of("370 버스")));
    }

    private JourneySession journey() {
        return JourneySession.builder()
                .userId(USER_ID)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(null)
                .build();
    }

    private watoo.grd.nextroute.application.journey.dto.RerouteOrigin origin() {
        return new watoo.grd.nextroute.application.journey.dto.RerouteOrigin(
                2, "ST-C", "BL-360", 4,
                watoo.grd.nextroute.application.journey.dto.RerouteOriginKind.TRANSFER_WAIT);
    }

    private RerouteProposalView view(RerouteProposalKind kind) {
        return new RerouteProposalView(1L, JOURNEY_ID, 1, origin(), kind, 1L, NOW,
                NOW.plusSeconds(120), List.of(candidate()));
    }
}
