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
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@DisplayName("경기·TAGO 탑승 위치(#264 PR-4)")
class RegionalRideLocatorTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final String ROUTE = "233000031";

    private final InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();
    private final JourneyLegRouteOptionRepository options = mock(JourneyLegRouteOptionRepository.class);
    private final RegionalRideLocator locator = new RegionalRideLocator(store, options);

    {
        // A(1) → WP(2, 경유 가상점) → B(3) → C(4) → D(5)
        store.replaceRoute(RegionalBusProvider.GG, ROUTE, "7770", null, List.of(
                stop("A", 1, false), stop("WP", 2, true), stop("B", 3, false),
                stop("C", 4, false), stop("D", 5, false)), NOW);
        given(options.findById(9L)).willReturn(Optional.of(JourneyLegRouteOption.builder()
                .journeyLegId(1L).optionIndex(0).laneIndex(0).sourceRouteId(ROUTE).routeName("7770").build()));
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

    private static RegionalRouteStop stop(String id, int seq, boolean waypoint) {
        return new RegionalRouteStop(id, id + "정류장", seq, null, null, waypoint, 37.5, 127.0 + seq / 100.0);
    }
}
