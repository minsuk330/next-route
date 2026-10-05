package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * "지금 눌러도 되는가"의 계약.
 *
 * <p>무게중심은 <b>거절해야 하는 경우</b>다. 한 번 잘못 통과하면 그 뒤로는 전부 외부 API 호출이고,
 * 지나간 환승에 대한 제안은 사용자가 할 수 있는 일이 없는 화면이 된다.
 */
@DisplayName("재탐색 적격성 판정")
class WindowCooldownEligibilityPolicyTest {

    private static final Long JOURNEY_ID = 42L;
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final TransferConnectionRepository transferRepository =
            mock(TransferConnectionRepository.class);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();

    /** 어떤 용도로 잠금을 물었는지. 물어본 사실 자체가 검증 대상이다. */
    private final List<RerouteCooldownPort.Scope> askedScopes = new ArrayList<>();

    private boolean cooldownAdmits = true;
    private WindowCooldownEligibilityPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new WindowCooldownEligibilityPolicy(transferRepository,
                new RerouteTriggerPolicy(properties),
                (journeyId, routeRevision, scope) -> {
                    askedScopes.add(scope);
                    return cooldownAdmits;
                },
                properties);
    }

    @Test
    @DisplayName("테스트 계정은 근거·시간 창·cooldown 없이 통과한다")
    void testUserSkipsWindowAndCooldown() {
        properties.setTestUserIds(java.util.Set.of(7L));
        cooldownAdmits = false;
        // 준비 시각이 1시간 뒤(문 밖)이고 근거도 없다.
        givenTransfer(transfer(2, null, TransferStatus.UNKNOWN));

        assertThat(policy.denialReason(journey(1), 0, NOW)).isEmpty();
        assertThat(askedScopes).as("cooldown 잠금을 태우지 않는다").isEmpty();
    }

    @Test
    @DisplayName("테스트 계정도 없는 환승·지나간 환승은 거절한다")
    void testUserStillNeedsAnUpcomingTransfer() {
        properties.setTestUserIds(java.util.Set.of(7L));
        givenTransfer(transfer(2, NOW.minusSeconds(1_800), TransferStatus.MISSED));

        assertThat(policy.denialReason(journey(5), 0, NOW)).contains("past-transfer");

        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());
        assertThat(policy.denialReason(journey(1), 3, NOW)).contains("no-transfer");
    }

    @Test
    @DisplayName("목록에 없는 계정은 평소대로 판정한다")
    void otherUsersAreUnaffected() {
        properties.setTestUserIds(java.util.Set.of(99L));
        givenTransfer(transfer(2, null, TransferStatus.UNKNOWN));

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("window");
    }

    @Test
    @DisplayName("조건이 다 맞으면 통과한다")
    void eligibleRequestPasses() {
        givenTransfer(transfer(2, NOW.plusSeconds(300), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(1), 0, NOW)).isEmpty();
    }

    @Test
    @DisplayName("이 개정본에 그 환승이 없으면 no-transfer")
    void missingTransferIsRejected() {
        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt())).thenReturn(Optional.empty());

        assertThat(policy.denialReason(journey(1), 3, NOW)).contains("no-transfer");
    }

    @Test
    @DisplayName("이미 지나간 환승이면 past-transfer — 시간 조건만으로는 통과해 버린다")
    void pastTransferIsRejected() {
        // 준비 시각은 30분 전이다. "준비 시각까지 600초 이내"는 음수 간격에도 참이라 시간
        // 조건만 보면 통과한다. 진행 단계가 그것을 막는 유일한 조건이다(R2).
        givenTransfer(transfer(2, NOW.minusSeconds(1_800), TransferStatus.MISSED));

        assertThat(policy.denialReason(journey(5), 0, NOW))
                .as("사용자는 이미 그 환승을 지났다. 제안해 봐야 할 수 있는 일이 없다")
                .contains("past-transfer");
    }

    @Test
    @DisplayName("본문 없이 온 요청(transferIndex=0)도 진행 단계로 걸린다")
    void defaultedTransferIndexIsRejectedWhenPassed() {
        // 옛 앱 버전은 필드를 안 보내고, 그때 transferIndex 는 0 으로 채워진다.
        givenTransfer(transfer(2, NOW.plusSeconds(60), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(4), 0, NOW)).contains("past-transfer");
    }

    @Test
    @DisplayName("진행 중인 대기 단계는 아직 지나지 않은 것이다")
    void currentLegIsStillUpcoming() {
        givenTransfer(transfer(2, NOW.plusSeconds(120), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(2), 0, NOW)).isEmpty();
    }

    @Test
    @DisplayName("근거가 없으면 window — GPS 가 끊기면 문이 저절로 닫힌다")
    void missingEvidenceClosesTheWindow() {
        givenTransfer(transfer(2, null, TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("window");
    }

    @Test
    @DisplayName("상태가 UNKNOWN 이면 window — '모르겠다'는 나빠진 것이 아니다")
    void unknownStatusClosesTheWindow() {
        givenTransfer(transfer(2, NOW.plusSeconds(120), TransferStatus.UNKNOWN));

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("window");
    }

    @Test
    @DisplayName("신뢰도가 UNKNOWN 이면 window")
    void unknownConfidenceClosesTheWindow() {
        TransferConnection transfer = transfer(2, NOW.plusSeconds(120), TransferStatus.AT_RISK);
        applyConfidence(transfer, CurrentLegArrival.Quality.UNKNOWN);
        givenTransfer(transfer);

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("window");
    }

    @Test
    @DisplayName("환승 준비 시각이 문 밖이면 window")
    void tooEarlyIsRejected() {
        givenTransfer(transfer(2, NOW.plusSeconds(1_200), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("window");
    }

    @Test
    @DisplayName("문 판정은 주기 평가와 같은 임계값을 쓴다")
    void windowThresholdIsShared() {
        properties.getTrigger().setDecisionWindowSeconds(60);
        givenTransfer(transfer(2, NOW.plusSeconds(120), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(1), 0, NOW))
                .as("임계값을 따로 읽으면 방아쇠는 열렸다고 세는데 버튼은 거절하는 상태가 생긴다")
                .contains("window");
    }

    @Test
    @DisplayName("최근에 이미 찾았으면 cooldown")
    void cooldownRejectsRepeatedPress() {
        cooldownAdmits = false;
        givenTransfer(transfer(2, NOW.plusSeconds(120), TransferStatus.AT_RISK));

        assertThat(policy.denialReason(journey(1), 0, NOW)).contains("cooldown");
    }

    @Test
    @DisplayName("cooldown 은 REQUEST 키를 쓴다 — shadow 방아쇠와 갈린다")
    void cooldownUsesRequestScope() {
        givenTransfer(transfer(2, NOW.plusSeconds(120), TransferStatus.AT_RISK));

        policy.denialReason(journey(1), 0, NOW);

        assertThat(askedScopes).containsExactly(RerouteCooldownPort.Scope.REQUEST);
    }

    @Test
    @DisplayName("앞에서 떨어질 요청은 잠금을 태우지 않는다")
    void rejectedRequestDoesNotBurnCooldown() {
        givenTransfer(transfer(2, NOW.minusSeconds(1_800), TransferStatus.MISSED));

        policy.denialReason(journey(5), 0, NOW);

        assertThat(askedScopes)
                .as("떨어질 요청이 잠금을 태우면 실제로 가능한 요청이 2분간 막힌다")
                .isEmpty();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenTransfer(TransferConnection transfer) {
        when(transferRepository.findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                anyLong(), anyInt(), anyInt())).thenReturn(Optional.of(transfer));
    }

    private TransferConnection transfer(int toLegIndex, Instant userReadyAt, TransferStatus status) {
        TransferConnection transfer = TransferConnection.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(1)
                .transferIndex(0)
                .fromLegIndex(toLegIndex - 1)
                .toLegIndex(toLegIndex)
                .status(status)
                .build();
        transfer.applyAssessment(status, NOW, userReadyAt, NOW.plusSeconds(240), 60,
                "REALTIME", CurrentLegArrival.Quality.GOOD.name(), NOW, NOW);
        return transfer;
    }

    private void applyConfidence(TransferConnection transfer, CurrentLegArrival.Quality quality) {
        transfer.applyAssessment(transfer.getStatus(), transfer.getCurrentLegArrivalAt(),
                transfer.getUserReadyAt(), transfer.getNextBusArrivalAt(),
                transfer.getTransferSlackSeconds(), transfer.getSource(), quality.name(),
                transfer.getCalculatedAt(), transfer.getDataObservedAt());
    }

    private JourneySession journey(int currentLegIndex) {
        JourneySession journey = JourneySession.builder()
                .userId(7L)
                .startedAt(NOW)
                .guidanceLeaseUntil(NOW.plusSeconds(180))
                .routeSnapshot(null)
                .build();
        ReflectionTestUtils.setField(journey, "id", JOURNEY_ID);
        journey.moveToLeg(currentLegIndex);
        return journey;
    }
}
