package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("경기·TAGO 탑승 위치(#264 PR-4)")
class RegionalRideLocatorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final String ROUTE = "233000031";

    private final InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();
    private final JourneyLegRouteOptionRepository options = mock(JourneyLegRouteOptionRepository.class);
    private final JourneyPlannedBusStopReader plannedReader = mock(JourneyPlannedBusStopReader.class);
    private final RegionalRideLocator locator = new RegionalRideLocator(store, options, plannedReader);
    private static final String TAGO_ROUTE = "DJB30300151";

    {
        // A(1) → WP(2, 경유 가상점) → B(3) → C(4) → D(5)
        store.replaceRoute(RegionalBusProvider.GG, ROUTE, "7770", null, List.of(
                stop("A", 1, false), stop("WP", 2, true), stop("B", 3, false),
                stop("C", 4, false), stop("D", 5, false)), NOW);
        given(options.findById(9L)).willReturn(Optional.of(JourneyLegRouteOption.builder()
                .journeyLegId(1L).optionIndex(0).laneIndex(0).sourceRouteId(ROUTE).routeName("7770").build()));
        given(plannedReader.realtimeStopIds(anyLong(), anyInt(), anyInt())).willReturn(List.of());
        // TAGO: A(1) → B(2) → X(3, GTFS 에 없는 실제 정류장 — 가상점 표시 없음) → C(4) → D(5)
        store.replaceRoute(RegionalBusProvider.TAGO, TAGO_ROUTE, "1001", "25", List.of(
                tagoStop("A", 1), tagoStop("B", 2), tagoStop("X", 3), tagoStop("C", 4), tagoStop("D", 5)), NOW);
        given(options.findById(10L)).willReturn(Optional.of(JourneyLegRouteOption.builder()
                .journeyLegId(2L).optionIndex(0).laneIndex(0).sourceRouteId(TAGO_ROUTE).routeName("1001").build()));
    }

    @Test
    @DisplayName("TAGO: GTFS 에 없는 실제 정류장이 끼어도 앱의 계획 정류장 목록으로 센다(대전 1001 둔곡교차로)")
    void tagoCountsOnPlannedStops() {
        JourneyLeg ride = tagoRide(2);
        given(plannedReader.realtimeStopIds(2L, 1, 1)).willReturn(List.of("A", "B", "C", "D"));

        RegionalRideLocator.Position position = locator.locate(ride, 180, NOW).position();

        // 앱: 남은 2개(C·D) → 지금 B(표 인덱스 1). 예전 규칙은 표에서 C·X 를 세어 X(인덱스 2)로 밀렸다.
        assertThat(position.currentIndex()).isEqualTo(1);
        assertThat(position.provider()).isEqualTo(RegionalBusProvider.TAGO);
        assertThat(position.isAlightable(2)).as("X 는 앱이 모르는 정류장").isFalse();
        assertThat(position.isAlightable(3)).isTrue();
        assertThat(position.realStopsAfterBoard(3)).as("C 는 앱 목록 2번째").isEqualTo(2);
        assertThat(position.realStopsAfterBoard(2)).isEqualTo(-1);
        assertThat(position.realStopsBetween(1, 4)).isEqualTo(2);
    }

    @Test
    @DisplayName("TAGO: 계획 목록이 없으면 예전 규칙(가상점만 빼고 세기)으로 물러난다")
    void tagoFallsBackWithoutPlannedStops() {
        RegionalRideLocator.Position position = locator.locate(tagoRide(2), 180, NOW).position();

        assertThat(position.currentIndex()).as("가상점 표시가 없어 X 도 센다").isEqualTo(2);
        assertThat(position.isAlightable(2)).isTrue();
    }

    @Test
    @DisplayName("계획 목록의 첫·끝이 표의 승차·하차와 다르면 믿지 않고 물러난다")
    void plannedStopsMustMatchEndpoints() {
        given(plannedReader.realtimeStopIds(2L, 1, 1)).willReturn(List.of("A", "B", "C"));

        assertThat(locator.locate(tagoRide(2), 180, NOW).position().currentIndex()).isEqualTo(2);
    }

    @Test
    @DisplayName("계획 목록에서 짚은 정류장이 표에 없으면 위치를 만들지 않는다")
    void plannedStopNotInTableIsOutOfRange() {
        given(plannedReader.realtimeStopIds(2L, 1, 1)).willReturn(List.of("A", "Q", "C", "D"));

        assertThat(locator.locate(tagoRide(2), 180, NOW).failureReason()).isEqualTo("position-out-of-range");
    }

    @Test
    @DisplayName("남은 정류장 수만큼 하차에서 거슬러 오른다 — 경유 가상점은 세지 않는다")
    void walksBackSkippingWaypoints() {
        RegionalRideLocator.Located located = locator.locate(ride("A", "D", 3), 180, NOW);

        assertThat(located.failed()).isFalse();
        // D 에서 실제 정류장 3개(C·B·A)를 거슬러 오르면 A(가상점 WP 는 건너뜀).
        assertThat(located.position().currentIndex()).isZero();
        assertThat(located.position().provider()).isEqualTo(RegionalBusProvider.GG);
        assertThat(locator.locate(ride("A", "D", 1), 180, NOW).position().currentIndex()).isEqualTo(3);
    }

    @Test
    @DisplayName("승차 이후 실제 정류장 수 — 스냅샷(GTFS) 정류장 순번과 같은 공간이다")
    void realStopsAfterBoardSkipsWaypoints() {
        RegionalRideLocator.Position position = locator.locate(ride("A", "D", 1), 180, NOW).position();

        assertThat(position.realStopsAfterBoard(2)).as("A 다음 WP·B 중 B 만").isEqualTo(1);
        assertThat(position.realStopsAfterBoard(4)).isEqualTo(3);
    }

    @Test
    @DisplayName("표에 없는 노선·조인 안 되는 정류장·낡은 진행은 모른다고 답한다")
    void unknownCases() {
        assertThat(locator.locate(ride("A", "Z", 1), 180, NOW).failureReason()).isEqualTo("stop-unjoined");
        JourneyLeg noOption = ride("A", "D", 1);
        ReflectionTestUtils.setField(noOption, "selectedRouteOptionId", null);
        assertThat(locator.locate(noOption, 180, NOW).failureReason()).isEqualTo("route-unknown");
        JourneyLeg stale = ride("A", "D", 1);
        ReflectionTestUtils.setField(stale, "progressObservedAt", NOW.minusSeconds(181));
        assertThat(locator.locate(stale, 180, NOW).failureReason()).isEqualTo("stale-progress");
    }

    private static JourneyLeg ride(String from, String to, int remainingStops) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(1L).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .sourceStartStopId(from).sourceEndStopId(to).plannedDurationSec(900).build();
        ReflectionTestUtils.setField(leg, "remainingStops", remainingStops);
        ReflectionTestUtils.setField(leg, "progressObservedAt", NOW);
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", 9L);
        return leg;
    }

    private static JourneyLeg tagoRide(int remainingStops) {
        JourneyLeg leg = ride("A", "D", remainingStops);
        ReflectionTestUtils.setField(leg, "journeyId", 2L);
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", 10L);
        return leg;
    }

    private static RegionalRouteStop tagoStop(String id, int seq) {
        return new RegionalRouteStop(id, id + "정류장", seq, null, 0, null, 36.4, 127.3 + seq / 100.0);
    }

    private static RegionalRouteStop stop(String id, int seq, boolean waypoint) {
        return new RegionalRouteStop(id, id + "정류장", seq, null, null, waypoint, 37.5, 127.0 + seq / 100.0);
    }
}
