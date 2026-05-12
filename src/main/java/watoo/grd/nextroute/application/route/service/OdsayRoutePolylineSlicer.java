package watoo.grd.nextroute.application.route.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylineData;
import watoo.grd.nextroute.application.route.dto.OdsayRoutePolylinePoint;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * DB에 저장된 전체 노선 폴리라인에서 지하철 subPath 구간을 잘라 반환한다.
 *
 * 권장: {@link #sliceByCoordinates} 사용.
 *   ODsay mapObj의 startIdx/endIdx 값은 stationId 성격으로, point index가 아니다.
 *   subPath의 startX/Y, endX/Y 좌표로 전체 polyline에서 occurrence를 찾아 slice한다.
 *
 *   경계 규칙:
 *    - 출발역: 같은 좌표가 연속 2개로 나타나는 occurrence 중 2번째 point
 *    - 도착역: 같은 좌표가 연속 2개로 나타나는 occurrence 중 1번째 point
 *    - 좌표 비교: tolerance(기본 1e-6) 이내
 *    - startIndex > endIndex: slice 후 reverse하여 진행 방향으로 반환
 *
 * Deprecated: {@link #slice} index 기반 메서드.
 *   ODsay search mapObj의 start/end 값에는 사용하지 말 것 (stationId 성격이라 OOB 발생).
 */
@Slf4j
@Component
public class OdsayRoutePolylineSlicer {

    public static final double DEFAULT_TOLERANCE = 1e-6;
    private static final int[] DEFAULT_DIRECTION_ANCHOR_PRECISIONS = {5, 4};

    /**
     * subPath의 출발/도착 좌표를 사용해 polyline을 자른다.
     *
     * 먼저 exact match(tolerance=0)로 occurrence를 찾고, 실패하면 DEFAULT_TOLERANCE 적용.
     * 각 좌표(시작/끝)는 독립적으로 fallback 결정한다.
     *
     * @return slice된 polyline. 좌표를 찾지 못하면 빈 리스트.
     */
    public List<CoordPoint> sliceByCoordinates(
            OdsayRoutePolylineData data,
            double startX, double startY,
            double endX, double endY) {
        return sliceByCoordinates(data, startX, startY, endX, endY, DEFAULT_TOLERANCE);
    }

    public List<CoordPoint> sliceByCoordinates(
            OdsayRoutePolylineData data,
            double startX, double startY,
            double endX, double endY,
            double tolerance) {

        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }

        List<Integer> startOccurrences = findOccurrencesPreferExact(data, startX, startY, tolerance);
        List<Integer> endOccurrences   = findOccurrencesPreferExact(data, endX, endY, tolerance);

        if (startOccurrences.isEmpty()) {
            log.warn("[Slicer] Start coord not found x={} y={} size={} tolerance={}",
                    startX, startY, data.size(), tolerance);
            return Collections.emptyList();
        }
        if (endOccurrences.isEmpty()) {
            log.warn("[Slicer] End coord not found x={} y={} size={} tolerance={}",
                    endX, endY, data.size(), tolerance);
            return Collections.emptyList();
        }

        // 출발역: occurrence 중 2번째 (출발 boundary). 1개뿐이면 1번째로 fallback.
        int startIndex;
        if (startOccurrences.size() >= 2) {
            startIndex = startOccurrences.get(1);
        } else {
            startIndex = startOccurrences.get(0);
            log.warn("[Slicer] Start coord has single occurrence at index={} (expected 2). Falling back to first.",
                    startIndex);
        }

        // 도착역: occurrence 중 1번째 (도착 boundary).
        int endIndex = endOccurrences.get(0);
        if (endOccurrences.size() < 2) {
            log.warn("[Slicer] End coord has single occurrence at index={} (expected 2).",
                    endIndex);
        }

        boolean reversed = startIndex > endIndex;
        int from = Math.min(startIndex, endIndex);
        int to   = Math.max(startIndex, endIndex);

        List<CoordPoint> slice = data.points().subList(from, to + 1).stream()
                .map(p -> new CoordPoint(p.x(), p.y()))
                .toList();

        if (!reversed) return slice;

        List<CoordPoint> mutable = new ArrayList<>(slice);
        Collections.reverse(mutable);
        return List.copyOf(mutable);
    }

    public List<CoordPoint> sliceByCoordinates(
            OdsayRoutePolylineData data,
            double startX, double startY,
            double endX, double endY,
            Double nextStationX, Double nextStationY) {
        return sliceByCoordinates(
                data,
                startX, startY,
                endX, endY,
                nextStationX, nextStationY,
                DEFAULT_TOLERANCE
        );
    }

    public List<CoordPoint> sliceByCoordinates(
            OdsayRoutePolylineData data,
            double startX, double startY,
            double endX, double endY,
            Double nextStationX, Double nextStationY,
            double tolerance) {
        return sliceByCoordinates(
                data,
                startX, startY,
                endX, endY,
                nextStationX, nextStationY,
                tolerance,
                DEFAULT_DIRECTION_ANCHOR_PRECISIONS
        );
    }

    public List<CoordPoint> sliceByCoordinates(
            OdsayRoutePolylineData data,
            double startX, double startY,
            double endX, double endY,
            Double nextStationX, Double nextStationY,
            double tolerance,
            int... directionAnchorPrecisions) {

        if (nextStationX == null || nextStationY == null || data == null || data.isEmpty()) {
            return sliceByCoordinates(data, startX, startY, endX, endY, tolerance);
        }

        List<BoundaryPair> startPairs = findBoundaryPairsPreferExact(data, startX, startY, tolerance);
        if (startPairs.isEmpty()) {
            return sliceByCoordinates(data, startX, startY, endX, endY, tolerance);
        }

        for (BoundaryPair startPair : startPairs) {
            BoundaryPair forwardAnchor = findMatchingBoundaryForward(
                    data, startPair.departureIndex(), nextStationX, nextStationY, directionAnchorPrecisions);
            BoundaryPair backwardAnchor = findMatchingBoundaryBackward(
                    data, startPair.arrivalIndex(), nextStationX, nextStationY, directionAnchorPrecisions);

            boolean useForward = shouldUseForward(data, startPair, forwardAnchor, backwardAnchor);
            if (useForward) {
                BoundaryPair endPair = findNearestEndPairForward(data, endX, endY, tolerance, startPair.departureIndex());
                if (endPair != null) {
                    return collectForward(data, startPair.departureIndex(), endPair.arrivalIndex());
                }
            }

            if (backwardAnchor != null) {
                BoundaryPair endPair = findNearestEndPairBackward(data, endX, endY, tolerance, startPair.arrivalIndex());
                if (endPair != null) {
                    return collectBackward(data, startPair.arrivalIndex(), endPair.departureIndex());
                }
            }
        }

        log.warn("[Slicer] Direction anchor not matched. Falling back to start/end slice. nextStation=({}, {}) start=({}, {}) end=({}, {}) anchorPrecisions={}",
                nextStationX, nextStationY, startX, startY, endX, endY, Arrays.toString(directionAnchorPrecisions));
        return sliceByCoordinates(data, startX, startY, endX, endY, tolerance);
    }

    /**
     * 1차로 exact match 시도, 비어 있으면 tolerance 적용 fallback.
     */
    private List<Integer> findOccurrencesPreferExact(
            OdsayRoutePolylineData data, double x, double y, double tolerance) {
        List<Integer> exact = findOccurrences(data, x, y, 0.0);
        if (!exact.isEmpty()) return exact;

        List<Integer> tolerant = findOccurrences(data, x, y, tolerance);
        if (!tolerant.isEmpty()) {
            log.debug("[Slicer] Exact match failed for x={} y={}, fallback to tolerance={} matched={}",
                    x, y, tolerance, tolerant.size());
        }
        return tolerant;
    }

    private List<Integer> findOccurrences(OdsayRoutePolylineData data, double x, double y, double tolerance) {
        List<Integer> result = new ArrayList<>();
        List<OdsayRoutePolylinePoint> points = data.points();
        for (int i = 0; i < points.size(); i++) {
            OdsayRoutePolylinePoint p = points.get(i);
            if (Math.abs(p.x() - x) <= tolerance && Math.abs(p.y() - y) <= tolerance) {
                result.add(i);
            }
        }
        return result;
    }

    private List<BoundaryPair> findBoundaryPairsPreferExact(
            OdsayRoutePolylineData data, double x, double y, double tolerance) {
        List<BoundaryPair> exact = findBoundaryPairs(data, x, y, 0.0);
        if (!exact.isEmpty()) return exact;
        return findBoundaryPairs(data, x, y, tolerance);
    }

    private List<BoundaryPair> findBoundaryPairs(
            OdsayRoutePolylineData data, double x, double y, double tolerance) {
        List<BoundaryPair> result = new ArrayList<>();
        for (int i = 0; i < data.size(); i++) {
            BoundaryPair pair = boundaryPairAt(data, i, tolerance);
            if (pair == null) continue;
            if (!matches(pair.x(), pair.y(), x, y, tolerance)) continue;
            result.add(pair);
        }
        return result;
    }

    private BoundaryPair findNextBoundaryForward(OdsayRoutePolylineData data, int fromIndex) {
        int size = data.size();
        for (int step = 1; step < size; step++) {
            int i = (fromIndex + step) % size;
            BoundaryPair pair = boundaryPairAt(data, i);
            if (pair != null) return pair;
        }
        return null;
    }

    private BoundaryPair findNextBoundaryBackward(OdsayRoutePolylineData data, int fromIndex) {
        int size = data.size();
        for (int step = 1; step < size; step++) {
            int arrivalIndex = Math.floorMod(fromIndex - step - 1, size);
            BoundaryPair pair = boundaryPairAt(data, arrivalIndex);
            if (pair != null) return pair;
        }
        return null;
    }

    private boolean shouldUseForward(
            OdsayRoutePolylineData data,
            BoundaryPair startPair,
            BoundaryPair forwardAnchor,
            BoundaryPair backwardAnchor) {
        if (forwardAnchor == null) return false;
        if (backwardAnchor == null) return true;

        int forwardDistance = forwardDistance(data.size(), startPair.departureIndex(), forwardAnchor.arrivalIndex());
        int backwardDistance = backwardDistance(data.size(), startPair.arrivalIndex(), backwardAnchor.departureIndex());
        return forwardDistance <= backwardDistance;
    }

    private BoundaryPair findMatchingBoundaryForward(
            OdsayRoutePolylineData data,
            int fromIndex,
            double nextStationX,
            double nextStationY,
            int[] precisions) {
        int size = data.size();
        for (int step = 1; step < size; step++) {
            int i = (fromIndex + step) % size;
            BoundaryPair pair = boundaryPairAt(data, i);
            if (pair != null && matchesDirectionAnchor(pair, nextStationX, nextStationY, precisions)) {
                return pair;
            }
        }
        return null;
    }

    private BoundaryPair findMatchingBoundaryBackward(
            OdsayRoutePolylineData data,
            int fromIndex,
            double nextStationX,
            double nextStationY,
            int[] precisions) {
        int size = data.size();
        for (int step = 1; step < size; step++) {
            int arrivalIndex = Math.floorMod(fromIndex - step - 1, size);
            BoundaryPair pair = boundaryPairAt(data, arrivalIndex);
            if (pair != null && matchesDirectionAnchor(pair, nextStationX, nextStationY, precisions)) {
                return pair;
            }
        }
        return null;
    }

    private BoundaryPair boundaryPairAt(OdsayRoutePolylineData data, int arrivalIndex) {
        return boundaryPairAt(data, arrivalIndex, DEFAULT_TOLERANCE);
    }

    private BoundaryPair boundaryPairAt(OdsayRoutePolylineData data, int arrivalIndex, double tolerance) {
        int departureIndex = (arrivalIndex + 1) % data.size();

        OdsayRoutePolylinePoint arrival = data.points().get(arrivalIndex);
        OdsayRoutePolylinePoint departure = data.points().get(departureIndex);
        if (!sameCoordinate(arrival, departure, tolerance)) return null;
        return new BoundaryPair(arrivalIndex, departureIndex, arrival.x(), arrival.y());
    }

    private BoundaryPair findNearestEndPairForward(
            OdsayRoutePolylineData data, double endX, double endY, double tolerance, int fromIndex) {
        List<BoundaryPair> pairs = findBoundaryPairsPreferExact(data, endX, endY, tolerance);
        BoundaryPair best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BoundaryPair pair : pairs) {
            int distance = forwardDistance(data.size(), fromIndex, pair.arrivalIndex());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pair;
            }
        }
        return best;
    }

    private BoundaryPair findNearestEndPairBackward(
            OdsayRoutePolylineData data, double endX, double endY, double tolerance, int fromIndex) {
        List<BoundaryPair> pairs = findBoundaryPairsPreferExact(data, endX, endY, tolerance);
        BoundaryPair best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (BoundaryPair pair : pairs) {
            int distance = backwardDistance(data.size(), fromIndex, pair.departureIndex());
            if (distance < bestDistance) {
                bestDistance = distance;
                best = pair;
            }
        }
        return best;
    }

    private List<CoordPoint> collectForward(OdsayRoutePolylineData data, int fromIndex, int toIndex) {
        List<CoordPoint> result = new ArrayList<>();
        int size = data.size();
        int index = fromIndex;
        while (true) {
            OdsayRoutePolylinePoint p = data.points().get(index);
            result.add(new CoordPoint(p.x(), p.y()));
            if (index == toIndex) break;
            index = (index + 1) % size;
        }
        return List.copyOf(result);
    }

    private List<CoordPoint> collectBackward(OdsayRoutePolylineData data, int fromIndex, int toIndex) {
        List<CoordPoint> result = new ArrayList<>();
        int size = data.size();
        int index = fromIndex;
        while (true) {
            OdsayRoutePolylinePoint p = data.points().get(index);
            result.add(new CoordPoint(p.x(), p.y()));
            if (index == toIndex) break;
            index = Math.floorMod(index - 1, size);
        }
        return List.copyOf(result);
    }

    private int forwardDistance(int size, int fromIndex, int toIndex) {
        return Math.floorMod(toIndex - fromIndex, size);
    }

    private int backwardDistance(int size, int fromIndex, int toIndex) {
        return Math.floorMod(fromIndex - toIndex, size);
    }

    private boolean sameCoordinate(OdsayRoutePolylinePoint a, OdsayRoutePolylinePoint b, double tolerance) {
        return matches(a.x(), a.y(), b.x(), b.y(), tolerance);
    }

    private boolean matches(double ax, double ay, double bx, double by, double tolerance) {
        return Math.abs(ax - bx) <= tolerance && Math.abs(ay - by) <= tolerance;
    }

    private boolean matchesDirectionAnchor(
            BoundaryPair boundary, double nextStationX, double nextStationY, int[] precisions) {
        int[] effectivePrecisions = (precisions == null || precisions.length == 0)
                ? DEFAULT_DIRECTION_ANCHOR_PRECISIONS
                : precisions;

        for (int precision : effectivePrecisions) {
            if (sameTruncated(boundary.x(), nextStationX, precision)
                    && sameTruncated(boundary.y(), nextStationY, precision)) {
                return true;
            }
        }
        return false;
    }

    private boolean sameTruncated(double a, double b, int precision) {
        return truncate(a, precision).compareTo(truncate(b, precision)) == 0;
    }

    private BigDecimal truncate(double value, int precision) {
        return BigDecimal.valueOf(value).setScale(precision, RoundingMode.DOWN);
    }

    private record BoundaryPair(int arrivalIndex, int departureIndex, double x, double y) {
    }

    /**
     * @deprecated ODsay search mapObj의 start/end 값에 사용 금지. 좌표 기반 {@link #sliceByCoordinates} 사용.
     *   유지되는 이유: 전체 노선(startIdx=-1, endIdx=-1)이나 명시적 index slice가 필요한 경우 호환용.
     */
    @Deprecated
    public List<CoordPoint> slice(OdsayRoutePolylineData data, int startIdx, int endIdx) {
        if (data == null || data.isEmpty()) {
            return Collections.emptyList();
        }

        int size = data.size();

        if (startIdx < 0 || endIdx < 0) {
            log.warn("[Slicer] Negative index startIdx={} endIdx={}", startIdx, endIdx);
            return Collections.emptyList();
        }

        if (startIdx >= size || endIdx >= size) {
            log.warn("[Slicer] Out-of-range index startIdx={} endIdx={} size={} — cache stale or routeId mismatch",
                    startIdx, endIdx, size);
            return Collections.emptyList();
        }

        boolean reversed = startIdx > endIdx;
        int from = Math.min(startIdx, endIdx);
        int to = Math.max(startIdx, endIdx);

        List<CoordPoint> slice = data.points().subList(from, to + 1).stream()
                .map(p -> new CoordPoint(p.x(), p.y()))
                .toList();

        if (!reversed) return slice;

        List<CoordPoint> mutable = new ArrayList<>(slice);
        Collections.reverse(mutable);
        return List.copyOf(mutable);
    }
}
