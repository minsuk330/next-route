package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 걷는 중인 사용자가 지금 어디쯤인지(#264 보드, 걷기 기점 A안).
 *
 * <p><b>서버는 좌표를 받지 않는다</b>(2026-08-12 결정). 그래서 앱이 보낸 진행 — 단계 안의 구간 순번
 * ({@code progressGeometrySegmentIndex})과 그 구간 진행률({@code progressFraction}) — 을 앱이 받은 것과
 * <b>같은 geometry</b> 위에 거리 기준으로 대입한다. 이 계약은 도착 예측
 * ({@link ClientGeometryProgressArrivalPredictor})이 쓰는 것과 같다. {@code progressLegFraction} 은
 * 서버가 뜻을 정의하지 않은 값이라 쓰지 않는다.
 *
 * <p>사용자가 경로를 벗어나면 틀린다 — 수용한 위험이다(#264 결정 A안).
 *
 * <p>모르면 모른다고 답한다:
 * <ul>
 *   <li>진행 보고가 {@code maxAge}(180초)보다 오래됐다 → 없음. 낡은 위치로 찾으면 "왔던 길로 돌아가라"가 된다</li>
 *   <li>진행 보고가 아직 없는데 단계 시작 뒤 {@code maxAge} 안이다 → 단계 시작점</li>
 *   <li>그 밖, geometry 가 없음 → 없음</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentPositionEstimator {

    private final GeometrySegmentAllocator allocator;

    public Optional<GeoPoint> estimate(JourneyLeg leg, LegGeometry geometry, Instant now,
                                       Duration maxAge) {
        if (geometry == null || geometry.segments() == null || geometry.segments().isEmpty()) {
            return Optional.empty();
        }
        Instant observedAt = leg.getProgressObservedAt();
        if (observedAt == null) {
            Instant activatedAt = leg.getActivatedAt();
            if (activatedAt != null && Duration.between(activatedAt, now).compareTo(maxAge) <= 0) {
                return firstPoint(geometry.segments());
            }
            return Optional.empty();
        }
        if (Duration.between(observedAt, now).compareTo(maxAge) > 0) {
            return Optional.empty();
        }
        int index = leg.getProgressGeometrySegmentIndex() == null ? 0 : leg.getProgressGeometrySegmentIndex();
        if (index < 0 || index >= geometry.segments().size()) {
            return Optional.empty();
        }
        double fraction = clamp(leg.getProgressFraction());
        return along(geometry.segments().get(index).points(), fraction);
    }

    /** 점 목록 위에서 거리 비율 {@code fraction} 인 지점. */
    Optional<GeoPoint> along(List<GeoPoint> points, double fraction) {
        if (points == null || points.isEmpty()) {
            return Optional.empty();
        }
        if (points.size() == 1) {
            return Optional.of(points.get(0));
        }
        double total = 0;
        for (int i = 1; i < points.size(); i++) {
            total += allocator.distanceMeters(points.get(i - 1), points.get(i));
        }
        if (total <= 0) {
            return Optional.of(points.get(0));
        }
        double target = total * fraction;
        double walked = 0;
        for (int i = 1; i < points.size(); i++) {
            GeoPoint from = points.get(i - 1);
            GeoPoint to = points.get(i);
            double step = allocator.distanceMeters(from, to);
            if (walked + step >= target) {
                double ratio = step <= 0 ? 0 : (target - walked) / step;
                return Optional.of(new GeoPoint(
                        from.x() + (to.x() - from.x()) * ratio,
                        from.y() + (to.y() - from.y()) * ratio));
            }
            walked += step;
        }
        return Optional.of(points.get(points.size() - 1));
    }

    private Optional<GeoPoint> firstPoint(List<SegmentGeometry> segments) {
        return segments.stream()
                .map(SegmentGeometry::points)
                .filter(points -> points != null && !points.isEmpty())
                .map(points -> points.get(0))
                .findFirst();
    }

    private static double clamp(BigDecimal fraction) {
        if (fraction == null) {
            return 0;
        }
        return Math.max(0, Math.min(1, fraction.doubleValue()));
    }
}
