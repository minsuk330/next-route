package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferArrival.Status;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 실시간·모델을 <b>묻지 못한</b> 버스 대기를 OTP 계획 탑승시각으로 채운다(search-regional-bus-wait PR-1b, source=SCHEDULE).
 *
 * <p>{@link TransferArrivalEnricher} 가 끝난 결과를 후처리한다 — 보강 wave 와 저하 보고(원래 status)는 이미 끝났고, 여기서 채운 값은
 * 다음 버스 계산에 들어가지 않는다(앞 버스가 없을 때 뒤 버스를 실시간으로 다시 묻는 연쇄는 하지 않는다, 사용자 결정 2026-11-09).
 * 외부 호출이 없다.
 *
 * <ul>
 *   <li>대상: lane 0 이고 status 가 정류장 매핑 실패·다른 지역 노선·앞 버스 결과 없음·한도·차단·오류·모델 없음. 물어봤는데 차가 없는
 *       {@code NO_VEHICLE} 와 기능 꺼짐 {@code DISABLED} 는 그대로 둔다(실측을 시간표로 덮지 않는다).</li>
 *   <li>정류장 도착 시각(보강이 계산한 {@code userArrivalAt})을 알면 대기 = 계획 탑승 − 도착(지금 출발 기준, 실시간과 같은 시계).
 *       60초 넘게 늦으면 계획 버스를 놓친 것이라 채우지 않는다(다음 버스 시각을 모른다). 출발시각을 지정한 검색(지금과 5분 넘게
 *       차이)도 시계가 달라 채우지 않는다.</li>
 *   <li>도착 시각을 모르면(앞 버스 결과 없음·기한 초과) 대기 = OTP 계획 대기({@code plannedWaitSeconds}), {@code conditional}.</li>
 *   <li>대기가 상한({@code schedule-max-wait-seconds})보다 길면 채우지 않는다. 앞에 지하철이 있으면 APPROXIMATE.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ScheduleWaitFiller {

    static final Set<Status> REPLACEABLE = EnumSet.of(Status.STOP_MAPPING_FAILED, Status.UNSUPPORTED_ROUTE,
            Status.UPSTREAM_UNAVAILABLE, Status.LIMITED, Status.BLOCKED, Status.ERROR, Status.MODEL_UNAVAILABLE);
    static final long MISS_TOLERANCE_SECONDS = 60;
    static final long DEPARTURE_SHIFT_SECONDS = 300;

    private static final int TRAFFIC_SUBWAY = 1;
    private static final int TRAFFIC_BUS = 2;

    private final TransferArrivalProperties props;
    private final RouteSearchMetrics metrics;

    /**
     * @param searchStartedAt 보강이 쓴 기준 시각(지금)
     * @param departureAt     요청 출발시각(지정 없으면 지금). null 이면 지금으로 본다
     */
    public RouteSearchResult fill(RouteSearchResult result, Instant searchStartedAt, Instant departureAt) {
        if (!props.isScheduleFallbackEnabled() || result == null || result.paths() == null) {
            return result;
        }
        boolean shifted = departureAt != null
                && Math.abs(Duration.between(searchStartedAt, departureAt).getSeconds()) > DEPARTURE_SHIFT_SECONDS;
        boolean changed = false;
        List<PathResult> paths = new ArrayList<>(result.paths().size());
        for (PathResult path : result.paths()) {
            if (path.subPaths() == null) {
                paths.add(path);
                continue;
            }
            List<SubPathResult> sps = new ArrayList<>(path.subPaths().size());
            boolean seenSubway = false;
            boolean pathChanged = false;
            for (SubPathResult sp : path.subPaths()) {
                if (sp.trafficType() == TRAFFIC_SUBWAY) {
                    seenSubway = true;
                }
                SubPathResult out = sp.trafficType() == TRAFFIC_BUS ? fillOne(sp, seenSubway, shifted) : sp;
                pathChanged |= out != sp;
                sps.add(out);
            }
            paths.add(pathChanged
                    ? new PathResult(path.pathKey(), path.pathType(), path.info(), sps, path.laneGraphics())
                    : path);
            changed |= pathChanged;
        }
        if (!changed) {
            return result;
        }
        return new RouteSearchResult(result.searchType(), result.busCount(), result.subwayCount(),
                result.trainCount(), result.airCount(), paths);
    }

    private SubPathResult fillOne(SubPathResult sp, boolean afterSubway, boolean shifted) {
        List<TransferArrival> arrivals = sp.transferArrivals();
        if (arrivals == null || arrivals.isEmpty()) {
            return sp;
        }
        int at = -1;
        for (int i = 0; i < arrivals.size(); i++) {
            if (arrivals.get(i).laneIndex() == 0) {
                at = i;
                break;
            }
        }
        if (at < 0 || !REPLACEABLE.contains(arrivals.get(at).status())) {
            return sp;
        }
        TransferArrival ta = arrivals.get(at);
        Instant board = sp.plannedBoardAt();
        if (board == null) {
            return skipped(sp, "no_plan");
        }
        Instant userAt = ta.userArrivalAt();
        boolean knowsArrival = userAt != null && ta.status() != Status.UPSTREAM_UNAVAILABLE;
        long wait;
        boolean conditional;
        Instant effectiveUserAt;
        if (knowsArrival) {
            if (shifted) {
                return skipped(sp, "departure_at");
            }
            long gap = Duration.between(userAt, board).getSeconds();
            if (gap < -MISS_TOLERANCE_SECONDS) {
                return skipped(sp, "missed");
            }
            wait = Math.max(0, gap);
            conditional = Boolean.TRUE.equals(ta.conditional());
            effectiveUserAt = userAt;
        } else {
            if (sp.plannedWaitSeconds() == null) {
                return skipped(sp, "no_plan");
            }
            wait = sp.plannedWaitSeconds();
            conditional = true;
            effectiveUserAt = board.minusSeconds(wait);
        }
        if (wait > props.getScheduleMaxWaitSeconds()) {
            return skipped(sp, "cap");
        }
        Status status = afterSubway ? Status.ARRIVAL_TIME_APPROXIMATE : Status.AVAILABLE;
        TransferArrival filled = new TransferArrival(ta.routeId(), ta.laneIndex(), TransferArrival.Source.SCHEDULE,
                status, ta.calculatedAt(), effectiveUserAt, board, wait, null, null, ta.basisLaneIndex(),
                conditional, ta.status().name());
        List<TransferArrival> out = new ArrayList<>(arrivals);
        out.set(at, filled);
        metrics.recordScheduleWait("filled", ta.status().name());
        return sp.withTransferArrivals(out);
    }

    private SubPathResult skipped(SubPathResult sp, String why) {
        metrics.recordScheduleWait("skipped", why);
        return sp;
    }
}
