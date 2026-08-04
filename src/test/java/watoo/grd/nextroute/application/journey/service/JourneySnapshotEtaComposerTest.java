package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyEtaView.NextAction;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("스냅샷에 현재 단계·환승·목적지 예측을 얹는다")
class JourneySnapshotEtaComposerTest {

    private static final long JOURNEY_ID = 7L;
    private static final long USER_ID = 42L;
    private static final int REVISION = 1;
    private static final Instant NOW = Instant.parse("2026-08-04T09:00:00Z");

    private JourneyLegRepository legRepository;
    private GetJourneyNavigationGeometryUseCase geometryUseCase;
    private CurrentLegArrivalService arrivalService;
    private TransferRiskEvaluator transferRiskEvaluator;
    private JourneySnapshotEtaComposer composer;

    /** 0=RIDE_BUS(현재), 1=TRANSFER_WALK(120초), 2=WAIT_BUS, 3=RIDE_BUS(600초), 4=WALK(180초) */
    private List<JourneyLeg> legs;

    @BeforeEach
    void setUp() {
        legRepository = mock(JourneyLegRepository.class);
        geometryUseCase = mock(GetJourneyNavigationGeometryUseCase.class);
        arrivalService = mock(CurrentLegArrivalService.class);
        transferRiskEvaluator = mock(TransferRiskEvaluator.class);
        composer = new JourneySnapshotEtaComposer(
                legRepository, geometryUseCase, arrivalService, transferRiskEvaluator);

        legs = List.of(
                leg(0, JourneyLegType.RIDE_BUS, 1200),
                leg(1, JourneyLegType.TRANSFER_WALK, 120),
                leg(2, JourneyLegType.WAIT_BUS, null),
                leg(3, JourneyLegType.RIDE_BUS, 600),
                leg(4, JourneyLegType.WALK, 180));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                JOURNEY_ID, REVISION)).willReturn(legs);
        given(geometryUseCase.get(anyLong(), anyLong(), anyInt()))
                .willReturn(new JourneyNavigationGeometry(JOURNEY_ID, REVISION, List.of(), null));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of());
    }

    @Test
    @DisplayName("현재 단계 ETA 를 절대 시각과 관측 시각까지 담는다")
    void fillsCurrentLegEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
        assertThat(result.eta().currentLeg().observedAt()).isEqualTo(NOW);
        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.GOOD);
    }

    @Test
    @DisplayName("버스를 타고 있으면 하차 예측을 담고 체크포인트는 담지 않는다")
    void fillsAlightingForBusOnly() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));

        var alighting = composer.compose(USER_ID, snapshot(0)).eta().alighting();

        assertThat(alighting).isNotNull();
        assertThat(alighting.remainingStops()).isEqualTo(3);
        assertThat(alighting.predictedAlightingAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("걷는 중에는 하차 예측이 없다")
    void noAlightingWhileWalking() {
        givenCurrentArrival(arrival(NOW.plusSeconds(100), CurrentLegArrival.Quality.GOOD));

        assertThat(composer.compose(USER_ID, snapshot(1)).eta().alighting()).isNull();
    }

    @Test
    @DisplayName("아직 지나지 않은 환승만 활성 환승으로 고른다")
    void picksUpcomingTransfer() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.CATCHABLE, 150L)));

        var transfer = composer.compose(USER_ID, snapshot(0)).eta().activeTransfer();

        assertThat(transfer).isNotNull();
        assertThat(transfer.transferIndex()).isZero();
        assertThat(transfer.transferSlackSeconds()).isEqualTo(150);
        // 성공 확률은 캘리브레이션이 준비된 뒤에 채운다
        assertThat(transfer.successProbability()).isNull();
    }

    @Test
    @DisplayName("이미 지나온 환승은 고르지 않는다")
    void skipsPassedTransfer() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.COMFORTABLE, 400L)));

        // 이미 4번 단계(마지막 걷기)까지 왔으면 2번 대기 단계 환승은 지나간 것이다
        assertThat(composer.compose(USER_ID, snapshot(4)).eta().activeTransfer()).isNull();
    }

    @Test
    @DisplayName("목적지 ETA 는 남은 단계를 이어 더하고 대기 단계는 다음 차량 도착으로 다시 기준 잡는다")
    void composesJourneyEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.CATCHABLE, 150L)));

        Instant journeyEta = composer.compose(USER_ID, snapshot(0)).eta().journeyEta();

        // 09:05 도착 → 도보 120초 → 대기 단계에서 09:10(다음 버스)로 재기준 → 승차 600초 → 걷기 180초
        assertThat(journeyEta).isEqualTo(Instant.parse("2026-08-04T09:23:00Z"));
    }

    @Test
    @DisplayName("대기 단계의 다음 차량 시각을 모르면 목적지 ETA 를 만들지 않는다")
    void unknownWaitMeansUnknownJourneyEta() {
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of());

        assertThat(composer.compose(USER_ID, snapshot(0)).eta().journeyEta()).isNull();
    }

    @Test
    @DisplayName("환승이 위험하면 다음 행동이 환승 준비이고 폴링을 좁힌다")
    void atRiskTransferChangesActionAndPolling() {
        givenCurrentArrival(arrival(NOW.plusSeconds(120), CurrentLegArrival.Quality.GOOD));
        given(transferRiskEvaluator.evaluate(anyLong(), anyInt(), any())).willReturn(List.of(
                assessment(0, 0, 2, TransferStatus.AT_RISK, 30L)));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().nextAction().type()).isEqualTo(NextAction.Type.PREPARE_TRANSFER);
        assertThat(result.recommendedPollSeconds()).isEqualTo(5);
    }

    @Test
    @DisplayName("마지막 걷기는 목적지로 가는 행동이다")
    void lastWalkGoesToDestination() {
        givenCurrentArrival(arrival(NOW.plusSeconds(180), CurrentLegArrival.Quality.GOOD));

        assertThat(composer.compose(USER_ID, snapshot(4)).eta().nextAction().type())
                .isEqualTo(NextAction.Type.WALK_TO_DESTINATION);
    }

    @Test
    @DisplayName("끝난 여정은 예측도 실시간 조회도 하지 않는다")
    void finishedJourneyIsUntouched() {
        JourneySnapshot finished = JourneySnapshot.of(JOURNEY_ID, JourneyStatus.COMPLETED,
                5L, 9L, REVISION, 4, NOW.plusSeconds(180), null, NOW, views());

        JourneySnapshot result = composer.compose(USER_ID, finished);

        assertThat(result.eta()).isNull();
        verify(transferRiskEvaluator, never()).evaluate(anyLong(), anyInt(), any());
        verify(geometryUseCase, never()).get(anyLong(), anyLong(), anyInt());
    }

    @Test
    @DisplayName("좌표 조회가 실패해도 예측을 포기하지 않는다")
    void geometryFailureIsTolerated() {
        given(geometryUseCase.get(anyLong(), anyLong(), anyInt()))
                .willThrow(new IllegalStateException("좌표 없음"));
        givenCurrentArrival(arrival(NOW.plusSeconds(300), CurrentLegArrival.Quality.APPROXIMATE));

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().arrivalAt()).isEqualTo(NOW.plusSeconds(300));
    }

    @Test
    @DisplayName("현재 단계 ETA 를 모르면 목적지 ETA 도 모른다")
    void unknownCurrentLegMeansUnknownJourneyEta() {
        givenCurrentArrival(CurrentLegArrival.unknown());

        JourneySnapshot result = composer.compose(USER_ID, snapshot(0));

        assertThat(result.eta().currentLeg().quality()).isEqualTo(CurrentLegArrival.Quality.UNKNOWN);
        assertThat(result.eta().journeyEta()).isNull();
        assertThat(result.eta().alighting()).isNull();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenCurrentArrival(CurrentLegArrival arrival) {
        given(arrivalService.predict(any(), any())).willReturn(arrival);
    }

    private JourneySnapshot snapshot(int currentLegIndex) {
        return JourneySnapshot.of(JOURNEY_ID, JourneyStatus.ACTIVE, 3L, 7L, REVISION,
                currentLegIndex, NOW.plusSeconds(180), null, NOW, views());
    }

    private List<JourneyLegView> views() {
        return legs.stream()
                .map(leg -> new JourneyLegView(leg.getId(), leg.getLegIndex(), leg.getType(),
                        leg.getState(), null, null, null, null, null,
                        leg.getPlannedDurationSec(), null, null, List.of()))
                .toList();
    }

    private CurrentLegArrival arrival(Instant at, CurrentLegArrival.Quality quality) {
        return new CurrentLegArrival(at,
                (int) (at.getEpochSecond() - NOW.getEpochSecond()),
                1840, 3, CurrentLegArrival.Source.CLIENT_GEOMETRY_PROGRESS, quality, NOW);
    }

    private TransferRiskAssessment assessment(int transferIndex, int fromLegIndex, int toLegIndex,
                                              TransferStatus status, Long slack) {
        return new TransferRiskAssessment(transferIndex, fromLegIndex, toLegIndex, status,
                NOW.plusSeconds(300), NOW.plusSeconds(450), NOW.plusSeconds(600),
                slack, CurrentLegArrival.Quality.GOOD, NOW);
    }

    private JourneyLeg leg(int index, JourneyLegType type, Integer plannedSeconds) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(JOURNEY_ID)
                .routeRevision(REVISION)
                .legIndex(index)
                .type(type)
                .state(JourneyLegState.ACTIVE)
                .plannedDurationSec(plannedSeconds)
                .build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        return leg;
    }
}
