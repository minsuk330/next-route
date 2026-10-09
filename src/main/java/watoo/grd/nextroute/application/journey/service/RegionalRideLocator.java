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
 * 왕복을 한 줄로 센다. 앱이 보내는 남은 정류장 수는 <b>앱이 가진 계획 정류장 목록</b>(검색 스냅샷의 GTFS 정류장,
 * {@link JourneyPlannedBusStopReader}) 기준이다. 그래서 그 목록에서 남은 수만큼 거슬러 올라가 지금 정류장 id 를
 * 찾고, 그 id 를 표에서 찾는다.
 *
 * <p>"가상점만 빼고 표에서 센다"로는 부족하다. TAGO 는 가상점 표시가 없는데도 GTFS 에 없는 실제 정류장이 있다
 * (대전 1001 둔곡교차로, 2026-10-07 실측). 그런 칸이 끼면 표에서 센 위치가 앱이 말한 위치보다 하차 쪽으로 밀린다.
 * 계획 정류장을 못 읽을 때만 예전 규칙(가상점만 빼고 세기)으로 물러난다.
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
    private final JourneyPlannedBusStopReader plannedStopReader;

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
        List<String> planned = plannedStops(ride, stops, board, alight);
        int current = planned.isEmpty()
                ? walkBackSkippingWaypoints(stops, board, alight, remainingStops)
                : walkBackOnPlanned(stops, planned, board, alight, remainingStops);
        if (current < board || current >= alight) {
            return Located.failed("position-out-of-range");
        }
        return Located.of(new Position(provider, routeId, stops, board, current, alight, planned));
    }

    /**
     * 앱이 세는 계획 정류장 목록. 첫·끝이 표의 승차·하차 정류장과 맞을 때만 쓴다 — 안 맞으면 다른 구간의 목록이라
     * 믿을 수 없으니 빈 목록(예전 규칙으로 물러남).
     */
    private List<String> plannedStops(JourneyLeg ride, List<RegionalRouteStop> stops, int board, int alight) {
        if (ride.getJourneyId() == null) {
            return List.of();
        }
        List<String> planned = plannedStopReader.realtimeStopIds(
                ride.getJourneyId(), ride.getRouteRevision(), ride.getLegIndex());
        if (planned.size() < 2
                || !stops.get(board).stopId().equals(planned.get(0))
                || !stops.get(alight).stopId().equals(planned.get(planned.size() - 1))) {
            return List.of();
        }
        return planned;
    }

    /** 계획 목록에서 남은 수만큼 거슬러 오른 정류장을 표에서 찾는다. 못 찾으면 -1. */
    private static int walkBackOnPlanned(List<RegionalRouteStop> stops, List<String> planned,
                                         int board, int alight, int remainingStops) {
        int plannedIndex = planned.size() - 1 - remainingStops;
        if (plannedIndex < 0) {
            return -1;
        }
        if (plannedIndex == 0) {
            return board;
        }
        String stopId = planned.get(plannedIndex);
        if (stopId == null) {
            return -1;
        }
        int found = indexOf(stops, stopId, board + 1);
        return found < alight ? found : -1;
    }

    /** 예전 규칙: 표에서 가상점만 빼고 센다. 계획 목록을 못 읽을 때만 쓴다. */
    private static int walkBackSkippingWaypoints(List<RegionalRouteStop> stops, int board, int alight,
                                                 int remainingStops) {
        int current = alight;
        int steps = remainingStops;
        while (steps > 0 && current > board) {
            current--;
            if (!isWaypoint(stops.get(current))) {
                steps--;
            }
        }
        return steps > 0 ? -1 : current;
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
     *
     * @param planned 앱이 세는 계획 정류장 id(승차 첫 원소). 못 읽었으면 빈 목록
     */
    public record Position(RegionalBusProvider provider, String routeId, List<RegionalRouteStop> stops,
                           int boardIndex, int currentIndex, int alightIndex, List<String> planned) {

        /**
         * 이 칸이 앱의 정류장 목록에 있는가 — 기점으로 열 수 있는가. 앱이 모르는 정류장(경유 가상점, GTFS 에 없는
         * TAGO 정류장)은 수락할 때 자를 자리가 없다.
         */
        public boolean isAlightable(int index) {
            RegionalRouteStop stop = stops.get(index);
            if (planned.isEmpty()) {
                return !isWaypoint(stop);
            }
            return plannedIndexOf(index) >= 0;
        }

        /** 계획 목록에서의 위치(승차 = 0). 계획 목록이 없거나 그 정류장이 없으면 -1. */
        public int plannedIndexOf(int index) {
            if (planned.isEmpty() || index < boardIndex) {
                return -1;
            }
            if (index == boardIndex) {
                return 0;
            }
            String stopId = stops.get(index).stopId();
            for (int i = 1; i < planned.size(); i++) {
                if (stopId.equals(planned.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        /**
         * 승차 다음부터 {@code index} 까지 승객이 타고 내릴 수 있는 정류장 수 — 앱 정류장 목록 순번과 같은 공간이다.
         * 계획 목록이 있으면 그 목록의 위치, 없으면 표에서 가상점만 빼고 센다. 계획 목록에 없는 칸이면 -1.
         */
        public int realStopsAfterBoard(int index) {
            if (!planned.isEmpty()) {
                return plannedIndexOf(index);
            }
            int count = 0;
            for (int i = boardIndex + 1; i <= index && i < stops.size(); i++) {
                if (!isWaypoint(stops.get(i))) {
                    count++;
                }
            }
            return count;
        }

        public int realStopsBetween(int fromExclusive, int toInclusive) {
            if (!planned.isEmpty()) {
                int from = plannedIndexOf(fromExclusive);
                int to = plannedIndexOf(toInclusive);
                return from < 0 || to < 0 ? -1 : to - from;
            }
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
