package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 경기·TAGO 버스를 타는 중 지금 어디쯤인지(#264 PR-4). 서울은 {@link BusRideLocator} 가 맡는다.
 *
 * <p>순번 공간은 <b>실시간 API 의 노선 정류장 표</b>(#267 {@code regional_bus_route_stop})다 — 경유 가상점을 포함해
 * 왕복을 한 줄로 센다. 앱이 보내는 남은 정류장 수는 GTFS(가상점 없음) 기준이라, 하차 정류장에서 <b>가상점을
 * 빼고</b> 그 수만큼 거슬러 올라가 지금 순번을 찾는다.
 *
 * <p>정류장 id 는 GTFS 와 API 가 같은 공간이다(#264 실측). 구간의 원천 id({@code sourceStartStopId}·
 * {@code sourceEndStopId})로 조인하고, 회차 노선의 같은 정류장은 승차 이후 첫 번째로 가른다(서울과 같은 규칙).
 * 조인이 안 되면 모른다고 답한다.
 */
@Component
@RequiredArgsConstructor
public class RegionalRideLocator {

    private final RegionalBusRouteStopStore stopStore;
    private final JourneyLegRouteOptionRepository routeOptionRepository;

    public Located locate(JourneyLeg ride, int progressMaxAgeSec, Instant now) {
        Integer remainingStops = ride.getRemainingStops();
        Instant observedAt = ride.getProgressObservedAt();
        if (remainingStops == null || remainingStops < 0 || observedAt == null) {
            return Located.failed("no-progress");
        }
        if (Duration.between(observedAt, now).getSeconds() > progressMaxAgeSec) {
            return Located.failed("stale-progress");
        }
        String routeId = sourceRouteId(ride).orElse(null);
        if (routeId == null || ride.getSourceStartStopId() == null || ride.getSourceEndStopId() == null) {
            return Located.failed("route-unknown");
        }
        RegionalBusProvider provider = RegionalBusProvider.GG;
        List<RegionalRouteStop> stops = stopStore.findRouteStops(provider, routeId);
        if (stops.isEmpty()) {
            provider = RegionalBusProvider.TAGO;
            stops = stopStore.findRouteStops(provider, routeId);
        }
        if (stops.isEmpty()) {
            return Located.failed("route-unknown");
        }
        int board = indexOf(stops, ride.getSourceStartStopId(), 0);
        int alight = board < 0 ? -1 : indexOf(stops, ride.getSourceEndStopId(), board + 1);
        if (board < 0 || alight < 0) {
            return Located.failed("stop-unjoined");
        }
        int current = alight;
        int steps = remainingStops;
        while (steps > 0 && current > board) {
            current--;
            if (!isWaypoint(stops.get(current))) {
                steps--;
            }
        }
        if (steps > 0 || current >= alight) {
            return Located.failed("position-out-of-range");
        }
        return Located.of(new Position(provider, routeId, stops, board, current, alight));
    }

    /** 이 주행 구간이 고른 노선의 원천 id(경기 GBIS·TAGO). 서울 id 가 아니다. */
    private Optional<String> sourceRouteId(JourneyLeg ride) {
        return Optional.ofNullable(ride.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getSourceRouteId)
                .filter(id -> !id.isBlank());
    }

    private static int indexOf(List<RegionalRouteStop> stops, String stopId, int from) {
        for (int i = from; i < stops.size(); i++) {
            if (stopId.equals(stops.get(i).stopId())) {
                return i;
            }
        }
        return -1;
    }

    static boolean isWaypoint(RegionalRouteStop stop) {
        return Boolean.TRUE.equals(stop.waypoint());
    }

    /**
     * 노선 정류장 목록 안의 위치. 인덱스는 목록 위치이고, API 순번은 {@code stops.get(i).seq()} 다.
     */
    public record Position(RegionalBusProvider provider, String routeId, List<RegionalRouteStop> stops,
                           int boardIndex, int currentIndex, int alightIndex) {

        /** 승차 다음부터 {@code index} 까지 승객이 타고 내릴 수 있는 정류장 수 — GTFS 정류장 순번과 같은 공간이다. */
        public int realStopsAfterBoard(int index) {
            int count = 0;
            for (int i = boardIndex + 1; i <= index && i < stops.size(); i++) {
                if (!isWaypoint(stops.get(i))) {
                    count++;
                }
            }
            return count;
        }

        public int realStopsBetween(int fromExclusive, int toInclusive) {
            int count = 0;
            for (int i = fromExclusive + 1; i <= toInclusive && i < stops.size(); i++) {
                if (!isWaypoint(stops.get(i))) {
                    count++;
                }
            }
            return count;
        }

        public int indexOfStop(String stopId, int from) {
            return RegionalRideLocator.indexOf(stops, stopId, from);
        }
    }

    public record Located(Position position, String failureReason) {

        static Located of(Position position) {
            return new Located(position, null);
        }

        static Located failed(String reason) {
            return new Located(null, reason);
        }

        public boolean failed() {
            return position == null;
        }
    }
}
