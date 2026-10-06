package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 후보 경로 조회(#259)의 계약.
 *
 * <p>가장 중요한 것은 {@code path} 가 <b>수락이 적용하는 값과 같은 원천</b>이라는 점이다. 조회와
 * 수락은 같은 {@link RerouteCandidateReader} 로 후보를 읽는다 — 여기서는 조회 결과가 그 reader 의
 * 결과를 가공 없이 내보내는지 본다.
 */
@DisplayName("재탐색 후보 경로 조회")
class RerouteCandidateRouteQueryServiceTest {

    private static final Long USER_ID = 7L;
    private static final Long JOURNEY_ID = 42L;
    private static final Long PROPOSAL_ID = 11L;
    private static final String CANDIDATE_ID = "c1";
    private static final Instant NOW = Instant.parse("2026-10-06T09:00:00Z");
    private static final String ROUTE_ID = "BL-RIDE";
    private static final Long OPTION_ID = 500L;
    /** 지금 타는 주행 단계. 계획 하차점 후보는 그 다음(대기, 2)부터 교체한다. */
    private static final int RIDE_LEG = 1;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final RerouteProposalRepository proposalRepository = mock(RerouteProposalRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyLegRouteOptionRepository routeOptionRepository =
            mock(JourneyLegRouteOptionRepository.class);
    private final BusRideLocator rideLocator = mock(BusRideLocator.class);
    private final RerouteCandidateReader reader = new RerouteCandidateReader(objectMapper);

    private final RerouteCandidateRouteQueryService service = new RerouteCandidateRouteQueryService(
            sessionRepository, proposalRepository, legRepository, routeOptionRepository, reader,
            rideLocator, new JourneyRerouteProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    private JourneySession journey;
    private JourneyLeg ride;

    @BeforeEach
    void setUp() {
        journey = JourneySession.builder().userId(USER_ID).startedAt(NOW.minusSeconds(1200)).build();
        ReflectionTestUtils.setField(journey, "id", JOURNEY_ID);
        journey.moveToLeg(RIDE_LEG);
        given(sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(JOURNEY_ID, USER_ID))
                .willReturn(Optional.of(journey));

        ride = JourneyLeg.builder()
                .journeyId(JOURNEY_ID).routeRevision(1).legIndex(RIDE_LEG)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .startStopId("ST-10").endStopId("ST-15")
                .startName("뚝섬역").endName("성수역")
                .plannedDurationSec(900)
                .build();
        ReflectionTestUtils.setField(ride, "routeId", ROUTE_ID);
        ReflectionTestUtils.setField(ride, "remainingStops", 4);
        ReflectionTestUtils.setField(ride, "progressObservedAt", NOW);
        ReflectionTestUtils.setField(ride, "selectedRouteOptionId", OPTION_ID);
        ReflectionTestUtils.setField(ride, "predictedEndAt", NOW.plusSeconds(600));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, 1)).willReturn(List.of(ride));
        given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(option("2412")));
    }

    // ── 같은 원천 ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("path 는 저장된 적용 재료(수락이 쓰는 값) 그대로다")
    void pathIsTheStoredApplyPath() {
        RerouteProposal proposal = givenProposal(plannedApply(), alight(127.05, 37.54));

        RerouteCandidateRoute route = service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID);

        RerouteCandidateApply.RemainingPathApply stored =
                (RerouteCandidateApply.RemainingPathApply) reader.read(proposal, CANDIDATE_ID).apply();
        assertThat((Object) objectMapper.valueToTree(route.path()))
                .as("다시 검색하거나 가공하면 사용자가 본 경로와 다른 경로가 적용된다")
                .isEqualTo((Object) objectMapper.valueToTree(stored.path()));
        assertThat(route.proposalId()).isEqualTo(PROPOSAL_ID);
        assertThat(route.candidateId()).isEqualTo(CANDIDATE_ID);
    }

    // ── 없음 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("없으면 404")
    class NotFound {

        @Test
        @DisplayName("남의 여정이면 JOURNEY_NOT_FOUND")
        void foreignJourney() {
            assertCode(() -> service.get(99L, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID),
                    JourneyErrorCode.JOURNEY_NOT_FOUND);
        }

        @Test
        @DisplayName("다른 여정의 제안이면 REROUTE_PROPOSAL_NOT_FOUND")
        void proposalOfOtherJourney() {
            RerouteProposal other = RerouteProposal.builder().journeyId(1L).userId(USER_ID)
                    .routeRevision(1).kind(RerouteProposalKind.REMAINING_PATH)
                    .baseCommandVersion(1).candidates(envelope(plannedApply(), null))
                    .expiresAt(NOW.plusSeconds(120)).build();
            given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(other));

            assertCode(() -> service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID),
                    JourneyErrorCode.REROUTE_PROPOSAL_NOT_FOUND);
        }

        @Test
        @DisplayName("제안에 없는 후보면 REROUTE_CANDIDATE_NOT_FOUND")
        void unknownCandidate() {
            givenProposal(plannedApply(), null);

            assertCode(() -> service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, "nope"),
                    JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
    }

    // ── 낡음 ────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("낡았으면 409 REROUTE_PROPOSAL_STALE — 수락과 같은 판정")
    class Stale {

        @Test
        @DisplayName("만료")
        void expired() {
            RerouteProposal proposal = givenProposal(plannedApply(), null);
            ReflectionTestUtils.setField(proposal, "expiresAt", NOW);

            assertStale();
        }

        @Test
        @DisplayName("이미 처리된 제안")
        void resolved() {
            givenProposal(plannedApply(), null).reject(NOW);

            assertStale();
        }

        @Test
        @DisplayName("개정본이 바뀐 여정")
        void revisionMoved() {
            givenProposal(plannedApply(), null);
            journey.bumpRouteRevision();

            assertStale();
        }

        @Test
        @DisplayName("교체 시작 단계를 이미 지났다")
        void passedLeg() {
            givenProposal(plannedApply(), null);
            journey.moveToLeg(3);

            assertStale();
        }

        @Test
        @DisplayName("끝난 여정")
        void journeyEnded() {
            givenProposal(plannedApply(), null);
            ReflectionTestUtils.setField(journey, "status", JourneyStatus.COMPLETED);

            assertStale();
        }

        private void assertStale() {
            assertCode(() -> service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID),
                    JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
    }

    // ── 지금 타는 구간 ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("ride — 지금 타는 구간")
    class Ride {

        @Test
        @DisplayName("계획 하차점 후보: 그 앞 주행 단계, 남은 정류장은 앱이 보낸 값")
        void plannedAlight() {
            givenProposal(plannedApply(), alight(127.05, 37.54));

            RerouteCandidateRoute.Ride ride =
                    service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID).ride();

            assertThat(ride.legIndex()).isEqualTo(RIDE_LEG);
            assertThat(ride.routeName()).isEqualTo("2412");
            assertThat(ride.fromStopName()).isEqualTo("뚝섬역");
            assertThat(ride.alightStopId()).isEqualTo("ST-15");
            assertThat(ride.alightStopName()).isEqualTo("하차점");
            assertThat(ride.alightX()).isEqualTo(127.05);
            assertThat(ride.alightY()).isEqualTo(37.54);
            assertThat(ride.stopCount()).isEqualTo(4);
            assertThat(ride.alightAt()).isEqualTo(NOW.plusSeconds(300));
            // 계획 하차점은 위치를 다시 잴 필요가 없다.
            verify(rideLocator, never()).locate(any(), anyInt(), any());
        }

        @Test
        @DisplayName("중간 하차 후보: 원점 정류장까지 남은 정류장을 지금 위치로 잰다")
        void midRide() {
            givenProposal(midRideApply(13), alight(127.07, 37.55));
            given(rideLocator.locate(ride, new JourneyRerouteProperties().getNextStop()
                    .getProgressMaxAgeSec(), NOW))
                    .willReturn(new BusRideLocator.Located(
                            new BusRideLocator.Position(List.of(), 10, 11, 15), null));

            RerouteCandidateRoute.Ride ride =
                    service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID).ride();

            assertThat(ride.legIndex()).isEqualTo(RIDE_LEG);
            assertThat(ride.alightStopId()).isEqualTo("ST-13");
            assertThat(ride.stopCount()).isEqualTo(2);
            assertThat(ride.alightX()).isEqualTo(127.07);
        }

        @Test
        @DisplayName("중간 하차인데 위치를 모르면 정류장 수만 비운다")
        void midRideWithoutPosition() {
            givenProposal(midRideApply(13), alight(127.07, 37.55));
            given(rideLocator.locate(any(), anyInt(), any()))
                    .willReturn(new BusRideLocator.Located(null, "stale-progress"));

            RerouteCandidateRoute.Ride ride =
                    service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID).ride();

            assertThat(ride).isNotNull();
            assertThat(ride.stopCount()).isNull();
        }

        @Test
        @DisplayName("지금 그 주행 단계가 아니면 ride 는 null 이다")
        void notRiding() {
            givenProposal(plannedApply(), alight(127.05, 37.54));
            journey.moveToLeg(0);

            RerouteCandidateRoute route = service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID);

            assertThat(route.ride()).isNull();
            assertThat(route.path()).isNotNull();
        }

        @Test
        @DisplayName("노선 이름을 못 풀어도 ride 는 나간다")
        void missingRouteName() {
            givenProposal(plannedApply(), alight(127.05, 37.54));
            given(routeOptionRepository.findById(OPTION_ID)).willReturn(Optional.of(option(" ")));

            assertThat(service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID).ride().routeName())
                    .isNull();
        }

        @Test
        @DisplayName("하차 정보가 없는 옛 저장 행은 주행 단계 값으로 채운다")
        void legacyRowWithoutAlight() {
            givenProposal(plannedApply(), null);

            RerouteCandidateRoute.Ride ride =
                    service.get(USER_ID, JOURNEY_ID, PROPOSAL_ID, CANDIDATE_ID).ride();

            assertThat(ride.alightStopName()).isEqualTo("성수역");
            assertThat(ride.alightX()).isNull();
            assertThat(ride.alightY()).isNull();
            assertThat(ride.alightAt()).isEqualTo(NOW.plusSeconds(600));
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteProposal givenProposal(RerouteCandidateApply apply,
                                          RerouteCandidateDisplay.AlightStop alight) {
        RerouteProposal proposal = RerouteProposal.builder()
                .journeyId(JOURNEY_ID).userId(USER_ID).routeRevision(1)
                .kind(RerouteProposalKind.REMAINING_PATH).baseCommandVersion(1)
                .candidates(envelope(apply, alight))
                .snapshotObservedAt(NOW).expiresAt(NOW.plusSeconds(120))
                .build();
        ReflectionTestUtils.setField(proposal, "id", PROPOSAL_ID);
        given(proposalRepository.findById(PROPOSAL_ID)).willReturn(Optional.of(proposal));
        return proposal;
    }

    private com.fasterxml.jackson.databind.JsonNode envelope(
            RerouteCandidateApply apply, RerouteCandidateDisplay.AlightStop alight) {
        RerouteCandidateDisplay display = new RerouteCandidateDisplay(NOW.plusSeconds(1800), 1800,
                300, 1, 120, "REALTIME", "GOOD", NOW, List.of("360 승차"), alight);
        return objectMapper.valueToTree(RerouteCandidateEnvelope.of(List.of(
                new RerouteCandidate(CANDIDATE_ID, RerouteProposalKind.REMAINING_PATH, apply,
                        display))));
    }

    /** 계획 하차점 후보 — 대기 단계(2)부터 교체한다. */
    private RerouteCandidateApply plannedApply() {
        return new RerouteCandidateApply.RemainingPathApply(RIDE_LEG + 1, candidatePath());
    }

    /** 주행 중 {@code seq} 번 정류장에서 내리는 후보. */
    private RerouteCandidateApply midRideApply(int seq) {
        return new RerouteCandidateApply.RemainingPathApply(RIDE_LEG, candidatePath(),
                new RerouteOrigin(RIDE_LEG, "ST-" + seq, ROUTE_ID, seq,
                        RerouteOriginKind.RIDE_INTERIOR));
    }

    private RerouteCandidateDisplay.AlightStop alight(double x, double y) {
        return new RerouteCandidateDisplay.AlightStop("ST-15", "하차점", NOW.plusSeconds(300),
                true, x, y);
    }

    private PathResult candidatePath() {
        return new PathResult(1,
                new PathInfo(25, 1500, 300, 1, "정류장A", "회사", null),
                List.of(SubPathFixture.bus(20).names("정류장A", "정류장F")
                        .busStops("ST-A", "ST-F")
                        .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
                List.of());
    }

    private JourneyLegRouteOption option(String routeName) {
        return JourneyLegRouteOption.builder().journeyLegId(1L).optionIndex(0).laneIndex(0)
                .routeId(ROUTE_ID).routeName(routeName).build();
    }

    private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                            JourneyErrorCode code) {
        assertThatThrownBy(call)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode()).isEqualTo(code));
    }
}
