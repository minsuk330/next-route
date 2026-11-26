package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.port.in.GetBusStopArrivalsUseCase;
import watoo.grd.nextroute.application.journey.dto.LaneReselection;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.RouteOptionSelectedBy;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("#614 PR-2 묶인 버스 대기 중 먼저 오는 노선 고르기")
class MergedBusLaneChooserTest {

    private static final Instant NOW = Instant.parse("2026-11-17T00:40:00Z");
    private static final long WAIT_ID = 30L;

    private JourneyLegRouteOptionRepository optionRepository;
    private GetBusStopArrivalsUseCase stopArrivals;
    private SimpleMeterRegistry registry;
    private MergedBusLaneChooser chooser;
    private JourneyLeg wait;
    private JourneyLeg ride;

    private final JourneyLegRouteOption opt402 = option(1L, 0, "402", "100100063", 700);
    private final JourneyLegRouteOption opt741 = option(2L, 1, "741", "100100120", 780);
    private final JourneyLegRouteOption opt140 = option(3L, 2, "140", "100100022", 905);

    private static JourneyLegRouteOption option(long id, int index, String name, String routeId, Integer rideSeconds) {
        JourneyLegRouteOption o = JourneyLegRouteOption.builder()
                .journeyLegId(WAIT_ID).optionIndex(index).laneIndex(index)
                .routeId(routeId).sourceRouteId("BL-" + name).routeName(name).rideSeconds(rideSeconds)
                .build();
        ReflectionTestUtils.setField(o, "id", id);
        return o;
    }

    private static JourneyLeg leg(long id, int index, JourneyLegType type, JourneyLegState state) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(index).type(type).state(state)
                .startStopId("121000009").startName("신분당선강남역").guidance(JourneyLegGuidance.EMPTY)
                .plannedDurationSec(700)
                .build();
        ReflectionTestUtils.setField(leg, "id", id);
        return leg;
    }

    private static BusStopArrivalResponse row(String routeId, String name, Integer predictSeconds) {
        return BusStopArrivalResponse.builder().routeId(routeId).routeName(name).predictTime1(predictSeconds)
                .arrivalMsg1(predictSeconds == null ? "출발대기" : (predictSeconds / 60) + "분후").build();
    }

    private static BusStopArrivalResponse ended(String routeId, String name) {
        return BusStopArrivalResponse.builder().routeId(routeId).routeName(name).arrivalMsg1("운행종료").build();
    }

    private void givenRows(StopArrivals.Status status, BusStopArrivalResponse... rows) {
        given(stopArrivals.getStopsArrivals(List.of("121000009")))
                .willReturn(List.of(new StopArrivals("121000009", status, List.of(rows))));
    }

    @BeforeEach
    void setUp() {
        optionRepository = mock(JourneyLegRouteOptionRepository.class);
        stopArrivals = mock(GetBusStopArrivalsUseCase.class);
        registry = new SimpleMeterRegistry();
        BusRealtimeStopIds stopIds = mock(BusRealtimeStopIds.class);
        given(stopIds.of(any())).willReturn(Optional.empty());
        chooser = new MergedBusLaneChooser(optionRepository, stopArrivals, stopIds, registry);
        wait = leg(WAIT_ID, 3, JourneyLegType.WAIT_BUS, JourneyLegState.ACTIVE);
        ride = leg(31L, 4, JourneyLegType.RIDE_BUS, JourneyLegState.PENDING);
        wait.selectRouteOption(1L, "100100063", RouteOptionSelectedBy.DEFAULT);
        ride.selectRouteOption(1L, "100100063", RouteOptionSelectedBy.DEFAULT);
        wait.applyBoardingPrediction(NOW.plusSeconds(1140), "REALTIME_ARRIVAL", "GOOD", NOW.minusSeconds(10));
        given(optionRepository.findByJourneyLegIdInAndDeletedAtIsNull(List.of(WAIT_ID)))
                .willReturn(List.of(opt402, opt741, opt140));
    }

    @Test
    @DisplayName("journey 516: 402 19분, 741 7분, 140 3분 → 140 으로(탑승시간 905초 함께)")
    void movesToEarliest() {
        givenRows(StopArrivals.Status.OK, row("100100063", "402", 1140), row("100100120", "741", 420),
                row("100100022", "140", 180), row("100100999", "N13", 60));

        LaneReselection chosen = chooser.choose(wait, ride, NOW).orElseThrow();

        assertThat(chosen.fromOptionId()).isEqualTo(1L);
        assertThat(chosen.toOptionId()).isEqualTo(3L);
        assertThat(chosen.toRouteId()).isEqualTo("100100022");
        assertThat(chosen.toRideSeconds()).isEqualTo(905);
        assertThat(chosen.waitLegIndex()).isEqualTo(3);
        assertThat(chosen.rideLegIndex()).isEqualTo(4);
        assertThat(chosen.fromWaitSeconds()).isEqualTo(1140);
        assertThat(chosen.toWaitSeconds()).isEqualTo(180);
    }

    @Test
    @DisplayName("60초 경계: 정확히 60초 먼저면 옮기고, 59초면 그대로")
    void hysteresis() {
        givenRows(StopArrivals.Status.OK, row("100100063", "402", 400), row("100100120", "741", 340));
        assertThat(chooser.choose(wait, ride, NOW)).isPresent();

        givenRows(StopArrivals.Status.OK, row("100100063", "402", 400), row("100100120", "741", 341));
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
    }

    @Test
    @DisplayName("지금 선택 줄이 운행종료면 다른 노선에 차가 있을 때 옮기고, 출발대기(시간 빈 줄)는 확정이 아니라 그대로")
    void currentHasNoBus() {
        givenRows(StopArrivals.Status.OK, ended("100100063", "402"), row("100100120", "741", 900));
        assertThat(chooser.choose(wait, ride, NOW)).get()
                .extracting(LaneReselection::toOptionId, LaneReselection::fromWaitSeconds)
                .containsExactly(2L, null);

        givenRows(StopArrivals.Status.OK, row("100100063", "402", null), row("100100120", "741", 900));
        assertThat(chooser.choose(wait, ride, NOW)).as("기점 출발대기 — 곧 출발할 수 있다").isEmpty();
    }

    @Test
    @DisplayName("직전 저장 예측이 없으면(탄 틈을 가릴 수 없음) 조회 없이 그대로")
    void noStoredPredictionKeeps() {
        ReflectionTestUtils.setField(wait, "predictedEndAt", null);

        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        verify(stopArrivals, never()).getStopsArrivals(any());
    }

    @Test
    @DisplayName("안 옮김: 조회 실패·막힘(OK 아님), 예외, 지금 선택 줄을 못 찾음, 지금 선택이 60초 안 도착")
    void keepsWhenUnsure() {
        givenRows(StopArrivals.Status.BLOCKED);
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        givenRows(StopArrivals.Status.LIMITED, ended("100100063", "402"), row("100100120", "741", 100));
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();

        given(stopArrivals.getStopsArrivals(List.of("121000009"))).willThrow(new IllegalStateException("down"));
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();

        stopArrivals = mock(GetBusStopArrivalsUseCase.class);
        BusRealtimeStopIds stopIds = mock(BusRealtimeStopIds.class);
        given(stopIds.of(any())).willReturn(Optional.empty());
        chooser = new MergedBusLaneChooser(optionRepository, stopArrivals, stopIds, registry);
        givenRows(StopArrivals.Status.OK, row("100100120", "741", 100));                 // 402 줄 없음
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        givenRows(StopArrivals.Status.OK, row("100100063", "402", 50), row("100100120", "741", 10));   // 402 곧 도착
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        givenRows(StopArrivals.Status.OK, row("100100063", "402", 90), row("100100120", "741", 10));   // 캐시 나이 몫 90초
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
    }

    @Test
    @DisplayName("안 옮김: 선택한 버스가 도착 중·막 떠남(직전 저장 예측이 [지금−120초, 지금+60초]) — 조회도 안 한다")
    void keepsWhenSelectedBusAtStop() {
        wait.applyBoardingPrediction(NOW.minusSeconds(30), "REALTIME_ARRIVAL", "GOOD", NOW.minusSeconds(40));

        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        verify(stopArrivals, never()).getStopsArrivals(any());

        wait.applyBoardingPrediction(NOW.plusSeconds(300), "REALTIME_ARRIVAL", "GOOD", NOW);
        givenRows(StopArrivals.Status.OK, row("100100063", "402", 300), row("100100120", "741", 100));
        assertThat(chooser.choose(wait, ride, NOW)).isPresent();
    }

    @Test
    @DisplayName("대상 아님: USER 선택, 후보 1개, 탑승 중, 아직 시작 전 대기 — 조회 안 한다")
    void notEligible() {
        wait.markRouteOptionSelectedBy(RouteOptionSelectedBy.USER);
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();

        JourneyLeg pending = leg(WAIT_ID, 3, JourneyLegType.WAIT_BUS, JourneyLegState.PENDING);
        pending.selectRouteOption(1L, "100100063", RouteOptionSelectedBy.DEFAULT);
        assertThat(chooser.choose(pending, ride, NOW)).isEmpty();

        JourneyLeg riding = leg(31L, 4, JourneyLegType.RIDE_BUS, JourneyLegState.ACTIVE);
        riding.selectRouteOption(1L, "100100063", RouteOptionSelectedBy.DEFAULT);
        assertThat(chooser.choose(riding, null, NOW)).isEmpty();

        wait.markRouteOptionSelectedBy(RouteOptionSelectedBy.DEFAULT);
        given(optionRepository.findByJourneyLegIdInAndDeletedAtIsNull(List.of(WAIT_ID))).willReturn(List.of(opt402));
        assertThat(chooser.choose(wait, ride, NOW)).isEmpty();
        verify(stopArrivals, never()).getStopsArrivals(any());
    }

    @Test
    @DisplayName("대조: 공급자 노선 id 먼저, 없으면 그 정류장에서 유일한 노선 번호. 번호가 겹치면 빼고, 못 맞춘 후보는 비교에서 뺀다")
    void matching() {
        JourneyLegRouteOption noId = option(4L, 3, "N13", null, null);
        JourneyLegRouteOption gyeonggi = JourneyLegRouteOption.builder().journeyLegId(WAIT_ID).optionIndex(4).laneIndex(4)
                .sourceRouteId("234000026").routeName("9401").build();   // 경기: routeId 비고 공급자 id 가 sourceRouteId
        ReflectionTestUtils.setField(gyeonggi, "id", 5L);
        var rows = List.of(row("100100063", "402", 300), row("X1", "N13", 30), row("X2", "741", 100), row("X3", "741", 50),
                row("234000026", "9401", 200), row("100100555", "9401", 400));

        var matched = MergedBusLaneChooser.match(List.of(opt402, opt741, noId, gyeonggi), rows);

        assertThat(matched).containsOnlyKeys(1L, 4L, 5L);   // 741 은 id 다르고 번호가 두 줄 → 뺌
        assertThat(matched.get(4L).routeName()).isEqualTo("N13");
        assertThat(matched.get(5L).predictTime1()).as("번호가 겹쳐도 공급자 id 로 맞춤").isEqualTo(200);
    }

    @Test
    @DisplayName("#626: 저장된 정류장 id 가 9자리가 아니어도(TAGO) 그 id 로 묻는다. 원천이 이미 뗀 9자리면 startStopId 가 비어도 그걸로 묻는다")
    void asksWithTheStoredOrSourceRealtimeStopId() {
        TagoCityCodeLookup tago = mock(TagoCityCodeLookup.class);
        given(tago.isRealtimePrefix("BSB")).willReturn(true);
        chooser = new MergedBusLaneChooser(optionRepository, stopArrivals, new BusRealtimeStopIds(tago), registry);

        ReflectionTestUtils.setField(wait, "startStopId", "BSB167850202");
        given(stopArrivals.getStopsArrivals(List.of("BSB167850202"))).willReturn(List.of(new StopArrivals(
                "BSB167850202", StopArrivals.Status.OK,
                List.of(row("100100063", "402", 1140), row("100100120", "741", 420)))));
        assertThat(chooser.choose(wait, ride, NOW)).get().extracting(LaneReselection::toOptionId).isEqualTo(2L);

        ReflectionTestUtils.setField(wait, "startStopId", null);
        ReflectionTestUtils.setField(wait, "sourceStartStopId", "233001674");
        given(stopArrivals.getStopsArrivals(List.of("233001674"))).willReturn(List.of(new StopArrivals(
                "233001674", StopArrivals.Status.OK,
                List.of(row("100100063", "402", 1140), row("100100120", "741", 420)))));
        assertThat(chooser.choose(wait, ride, NOW)).get().extracting(LaneReselection::toOptionId).isEqualTo(2L);
    }
}
