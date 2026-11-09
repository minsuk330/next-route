package watoo.grd.nextroute.application.route.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 도보(trafficType=3) subPath의 시작/끝 좌표·출구·이름을 결정한다.
 *
 * 인접 대중교통 구간의 종류에 따라 다른 규칙 적용:
 *  - 지하철(trafficType=1): 출구 좌표가 둘 다 있으면 출구 우선, 부분 누락이면 X/Y 폴백
 *  - 버스(trafficType=2): X/Y 그대로
 *  - 없음(첫/마지막 도보): 사용자 요청 좌표
 *
 * 이름 우선순위:
 *  - 출구명("역명 11번 출구") > 역명/정류장명 > 사용자 입력명 > 더미("출발"/"도착")
 *
 * TMAP 을 부르지 않는다. ODsay 경로는 {@link WalkSegmentEnricher} 가 TMAP 보강과 함께 이 규칙을 쓰고,
 * OTP 경로는 {@link #fillWalkEndpoints} 로 이 규칙만 쓴다.
 */
@Component
public class WalkCoordResolver {

    private static final int SUBWAY = 1;
    private static final int BUS = 2;
    private static final int WALK = 3;

    /**
     * 모든 경로의 도보 구간에 시작·끝 이름과 좌표를 위 규칙으로 채운다. 형상·거리·소요시간 같은 나머지 값은 그대로 둔다.
     *
     * <p>OTP 경로용(#468). OTP 도보 구간은 정류장 목록이 없어 이름·좌표가 비어 나오고, ODsay 경로처럼
     * {@link WalkSegmentEnricher} 를 타지도 않는다. 비워 두면 그 결과로 시작한 여정의 첫·끝 걷기 이름이 null 이라
     * 앱이 '출발지'·'도착지'·'다음 장소까지 걷기'로 그린다. 장소 목적지 경로에서 목적지 이름이 남는 곳은 여기뿐이다 —
     * 여정 시작 요청은 이름을 다시 보내지 않고 검색 스냅샷의 구간을 그대로 쓴다.
     *
     * <p>이웃 구간이 지하철·버스가 아닌 쪽(도보 뒤 도보 등)은 건드리지 않는다. 그 점이 어디인지 모르는데 출발지·목적지
     * 이름을 적으면 경로 중간이 목적지로 표시된다.
     */
    public RouteSearchResult fillWalkEndpoints(RouteSearchResult result,
                                               double requestedStartX, double requestedStartY,
                                               String requestedStartName,
                                               double requestedEndX, double requestedEndY,
                                               String requestedEndName) {
        if (result == null || result.paths() == null || result.paths().isEmpty()) {
            return result;
        }
        List<PathResult> paths = new ArrayList<>(result.paths().size());
        for (PathResult path : result.paths()) {
            paths.add(fillWalkEndpoints(path, requestedStartX, requestedStartY, requestedStartName,
                    requestedEndX, requestedEndY, requestedEndName));
        }
        return new RouteSearchResult(result.searchType(), result.busCount(), result.subwayCount(),
                result.trainCount(), result.airCount(), paths,
                result.meta(), result.searchSessionId(), result.expiresAt());
    }

    private PathResult fillWalkEndpoints(PathResult path,
                                         double requestedStartX, double requestedStartY,
                                         String requestedStartName,
                                         double requestedEndX, double requestedEndY,
                                         String requestedEndName) {
        List<SubPathResult> subs = path.subPaths();
        if (subs == null || subs.stream().noneMatch(sp -> sp.trafficType() == WALK)) {
            return path;
        }
        List<SubPathResult> filled = new ArrayList<>(subs.size());
        for (int i = 0; i < subs.size(); i++) {
            SubPathResult sp = subs.get(i);
            if (sp.trafficType() != WALK) {
                filled.add(sp);
                continue;
            }
            SubPathResult prev = i > 0 ? subs.get(i - 1) : null;
            SubPathResult next = i < subs.size() - 1 ? subs.get(i + 1) : null;
            Resolved start = knowsPoint(prev)
                    ? resolveStart(prev, requestedStartX, requestedStartY, requestedStartName) : null;
            Resolved end = knowsPoint(next)
                    ? resolveEnd(next, requestedEndX, requestedEndY, requestedEndName) : null;
            filled.add(withEndpoints(sp, start, end));
        }
        return new PathResult(path.pathKey(), path.pathType(), path.info(), filled, path.laneGraphics());
    }

    /** 경로 끝(null)이거나 지하철·버스면 도보의 그쪽 끝점을 안다. */
    private static boolean knowsPoint(SubPathResult neighbor) {
        return neighbor == null || neighbor.trafficType() == SUBWAY || neighbor.trafficType() == BUS;
    }

    /** 끝점만 바꾼 복사본. null 인 쪽은 원래 값을 둔다. 출구 좌표는 출구 번호가 있을 때만 싣는다. */
    private static SubPathResult withEndpoints(SubPathResult sp, Resolved start, Resolved end) {
        return new SubPathResult(
                sp.trafficType(), sp.sectionTime(), sp.distance(),
                sp.lanes(), sp.stations(),
                start == null ? sp.startName() : start.name(),
                end == null ? sp.endName() : end.name(),
                start == null ? sp.startX() : Double.valueOf(start.x()),
                start == null ? sp.startY() : Double.valueOf(start.y()),
                end == null ? sp.endX() : Double.valueOf(end.x()),
                end == null ? sp.endY() : Double.valueOf(end.y()),
                sp.trainType(), sp.payment(), sp.startId(), sp.way(), sp.wayCode(),
                sp.polyline(),
                start == null ? sp.startExitNo() : start.exitNo(),
                start == null ? sp.startExitX() : exitCoord(start, start.x()),
                start == null ? sp.startExitY() : exitCoord(start, start.y()),
                end == null ? sp.endExitNo() : end.exitNo(),
                end == null ? sp.endExitX() : exitCoord(end, end.x()),
                end == null ? sp.endExitY() : exitCoord(end, end.y()),
                sp.walkSteps(),
                sp.startLocalStationID(), sp.endLocalStationID(),
                sp.startArsID(), sp.endArsID(), sp.endID(),
                sp.walkTotalTimeSeconds(),
                sp.transferArrivals(),
                sp.trainKind(),
                sp.plannedBoardAt(), sp.plannedAlightAt(), sp.plannedWaitSeconds()
        );
    }

    private static Double exitCoord(Resolved point, double value) {
        return point.exitNo() != null ? value : null;
    }

    /**
     * 도보의 시작점을 결정한다 (이전 subPath의 도착점이 도보의 시작).
     *
     * @param prev 도보 직전 subPath. null이면 도보가 첫 구간 = 출발지.
     */
    public Resolved resolveStart(SubPathResult prev,
                                 double requestedX, double requestedY,
                                 String requestedName) {
        if (prev == null) {
            return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "출발"));
        }

        if (prev.trafficType() == SUBWAY) {
            Double exitX = prev.endExitX();
            Double exitY = prev.endExitY();
            String exitNo = prev.endExitNo();
            if (exitX != null && exitY != null) {
                return new Resolved(exitX, exitY, exitNo,
                        composeStationExitName(prev.endName(), exitNo));
            }
            // 부분 누락 또는 출구 정보 없음 → endX/Y 폴백
            return new Resolved(
                    nullSafe(prev.endX(), requestedX),
                    nullSafe(prev.endY(), requestedY),
                    null,
                    fallbackName(prev.endName(), requestedName)
            );
        }

        if (prev.trafficType() == BUS) {
            return new Resolved(
                    nullSafe(prev.endX(), requestedX),
                    nullSafe(prev.endY(), requestedY),
                    null,
                    fallbackName(prev.endName(), requestedName)
            );
        }

        // 도보→도보 이상한 경우 — 요청 좌표 사용
        return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "출발"));
    }

    /**
     * 도보의 끝점을 결정한다 (다음 subPath의 시작점이 도보의 끝).
     *
     * @param next 도보 직후 subPath. null이면 도보가 마지막 구간 = 도착지.
     */
    public Resolved resolveEnd(SubPathResult next,
                               double requestedX, double requestedY,
                               String requestedName) {
        if (next == null) {
            return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "도착"));
        }

        if (next.trafficType() == SUBWAY) {
            Double exitX = next.startExitX();
            Double exitY = next.startExitY();
            String exitNo = next.startExitNo();
            if (exitX != null && exitY != null) {
                return new Resolved(exitX, exitY, exitNo,
                        composeStationExitName(next.startName(), exitNo));
            }
            return new Resolved(
                    nullSafe(next.startX(), requestedX),
                    nullSafe(next.startY(), requestedY),
                    null,
                    fallbackName(next.startName(), requestedName)
            );
        }

        if (next.trafficType() == BUS) {
            return new Resolved(
                    nullSafe(next.startX(), requestedX),
                    nullSafe(next.startY(), requestedY),
                    null,
                    fallbackName(next.startName(), requestedName)
            );
        }

        return new Resolved(requestedX, requestedY, null, fallbackName(requestedName, "도착"));
    }

    private double nullSafe(Double value, double fallback) {
        return value != null ? value : fallback;
    }

    private String composeStationExitName(String stationName, String exitNo) {
        if (isBlank(stationName)) return stationName != null ? stationName : "";
        if (isBlank(exitNo)) return stationName;
        return stationName + " " + exitNo + "번 출구";
    }

    private String fallbackName(String... candidates) {
        for (String c : candidates) {
            if (!isBlank(c)) return c;
        }
        return candidates.length > 0 ? candidates[candidates.length - 1] : "";
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public record Resolved(double x, double y, String exitNo, String name) {}
}
