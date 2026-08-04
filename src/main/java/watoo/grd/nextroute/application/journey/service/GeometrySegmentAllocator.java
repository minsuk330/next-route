package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;

import java.util.ArrayList;
import java.util.List;

/**
 * 좌표 목록을 segment 로 쪼개고 leg 소요시간을 거리 비율로 나눠 준다.
 *
 * <p>여기가 SC-9 의 계산식이 사는 곳이다.
 * <pre>
 * segmentPlannedSeconds = legPlannedSeconds × segmentDistance / legTotalDistance
 * </pre>
 *
 * <p>왜 서버가 미리 나눠 주는가 — 앱과 서버가 각자 나누면 같은 지점에서 서로 다른 남은 시간을
 * 말하게 된다. 배분은 좌표가 정해지면 변하지 않는 값이라 한 번만 계산해 내려보내는 편이 낫다.
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Component
public class GeometrySegmentAllocator {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * 좌표를 잇는 선분마다 segment 를 하나 만든다. 버스처럼 이미 구간이 나뉘어 있으면
     * {@link #allocate(List, Integer)} 대신 {@link #distribute(List, Integer)} 를 쓴다.
     *
     * @param points            leg 전체 좌표. 2개 미만이면 빈 목록
     * @param legPlannedSeconds leg 전체 소요시간(초). null 이면 배분 시간을 채우지 않는다
     */
    public List<SegmentGeometry> allocate(List<GeoPoint> points, Integer legPlannedSeconds) {
        if (points == null || points.size() < 2) {
            return List.of();
        }
        List<Double> distances = new ArrayList<>(points.size() - 1);
        for (int i = 0; i < points.size() - 1; i++) {
            distances.add(distanceMeters(points.get(i), points.get(i + 1)));
        }
        List<Integer> seconds = splitByDistance(distances, legPlannedSeconds);

        List<SegmentGeometry> segments = new ArrayList<>(distances.size());
        for (int i = 0; i < distances.size(); i++) {
            segments.add(new SegmentGeometry(
                    i, null, null, null,
                    round(distances.get(i)),
                    seconds.get(i),
                    // 실측 좌표로 만든 구간이라 정상이 기본이다. 직선 대체는 부르는 쪽이 낮춘다.
                    GeometryQuality.OK, null,
                    List.of(points.get(i), points.get(i + 1))));
        }
        return segments;
    }

    /**
     * 이미 나뉘어 있는 segment 들에 거리와 배분 시간만 채운다. 버스 section 이 이 경우다.
     *
     * <p>좌표가 없는 segment(품질 {@code FAILED})도 목록에서 빼지 않는다. 빼면 앱이 "구간이
     * 몇 개인지"를 서버와 다르게 세게 되고, 그러면 진행 순번이 어긋난다.
     */
    public List<SegmentGeometry> distribute(List<SegmentGeometry> segments, Integer legPlannedSeconds) {
        if (segments == null || segments.isEmpty()) {
            return List.of();
        }
        List<Double> distances = new ArrayList<>(segments.size());
        for (SegmentGeometry segment : segments) {
            distances.add(polylineLengthMeters(segment.points()));
        }
        List<Integer> seconds = splitByDistance(distances, legPlannedSeconds);

        List<SegmentGeometry> filled = new ArrayList<>(segments.size());
        for (int i = 0; i < segments.size(); i++) {
            filled.add(segments.get(i).withAllocation(i, round(distances.get(i)), seconds.get(i)));
        }
        return filled;
    }

    /** 좌표를 이어 붙인 총 길이(m). */
    public double polylineLengthMeters(List<GeoPoint> points) {
        if (points == null || points.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 0; i < points.size() - 1; i++) {
            total += distanceMeters(points.get(i), points.get(i + 1));
        }
        return total;
    }

    /**
     * 거리 비율로 초를 나눈다.
     *
     * <p>반올림 오차를 마지막 칸에 몰아 합이 원래 값과 정확히 같게 만든다. 칸마다 반올림하면
     * 합이 leg 소요시간과 달라지고, 그 차이가 남은 시간 계산에 그대로 남는다.
     *
     * <p>총 거리가 0 이면(같은 지점 반복) 균등 분배한다. 거리 비율을 쓸 수 없기 때문이다.
     */
    private List<Integer> splitByDistance(List<Double> distances, Integer totalSeconds) {
        int count = distances.size();
        List<Integer> result = new ArrayList<>(count);
        if (totalSeconds == null) {
            for (int i = 0; i < count; i++) {
                result.add(null);
            }
            return result;
        }
        double total = distances.stream().mapToDouble(Double::doubleValue).sum();
        int assigned = 0;
        for (int i = 0; i < count - 1; i++) {
            int seconds = total <= 0
                    ? totalSeconds / count
                    : (int) Math.round(totalSeconds * distances.get(i) / total);
            result.add(seconds);
            assigned += seconds;
        }
        result.add(Math.max(0, totalSeconds - assigned));
        return result;
    }

    private double distanceMeters(GeoPoint a, GeoPoint b) {
        double lat1 = Math.toRadians(a.y());
        double lat2 = Math.toRadians(b.y());
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(b.x() - a.x());
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(lat1) * Math.cos(lat2) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }

    private double round(double meters) {
        return Math.round(meters * 10.0) / 10.0;
    }
}
