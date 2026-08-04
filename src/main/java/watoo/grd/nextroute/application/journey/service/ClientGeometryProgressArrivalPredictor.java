package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Quality;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival.Source;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.port.out.CurrentLegArrivalPredictor;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Duration;
import java.time.Instant;

/**
 * 이동 단계의 종료 시각을 앱 GPS 진행도로 구한다.
 *
 * <p>버스·지하철·걷기·환승걷기가 모두 이 구현을 쓴다. 현재 버스의 실시간 차량 위치나 지하철
 * 열차 번호는 쓰지 않는다 — 실제 탑승 차량을 식별하지 않는다는 결정 때문이고, 그 덕분에 실시간
 * API 가 죽어도 이 계산은 계속 된다.
 *
 * <h2>세 갈래</h2>
 * <ol>
 *   <li>최근 관측이 있다 → 그 관측 시각 기준으로 절대 ETA 를 고정한다</li>
 *   <li>관측이 오래됐다(터널·GPS 소실) → <b>이전에 고정한 ETA 를 그대로 유지</b>하고 품질만 낮춘다</li>
 *   <li>관측이 아예 없다 → 계획 소요시간으로 근사하고 품질을 낮춘다</li>
 * </ol>
 *
 * <p>둘째가 이 클래스의 핵심이다. 관측이 멈춘 동안 "지금 + 남은 시간"으로 다시 계산하면 조회할
 * 때마다 도착 시각이 뒤로 밀려 남은 시간이 줄지 않는다. 사용자가 보기에 시계가 멈춘 것과 같다.
 */
@Component
@RequiredArgsConstructor
public class ClientGeometryProgressArrivalPredictor implements CurrentLegArrivalPredictor {

    private final StaticGeometryProgressEtaCalculator calculator;
    private final JourneyNavigationProperties properties;

    @Override
    public boolean supports(JourneyLegType type) {
        return type == JourneyLegType.RIDE_BUS
                || type == JourneyLegType.RIDE_SUBWAY
                || type == JourneyLegType.WALK
                || type == JourneyLegType.TRANSFER_WALK;
    }

    @Override
    public CurrentLegArrival predict(JourneyLeg leg, LegGeometry geometry, Instant now) {
        Instant observedAt = leg.getProgressObservedAt();
        if (observedAt == null) {
            return fromPlannedDuration(leg);
        }
        if (isStale(observedAt, now)) {
            return keepLastFixedArrival(leg, observedAt);
        }
        if (geometry == null || geometry.segments().isEmpty()) {
            return fromPlannedDuration(leg);
        }

        int segmentIndex = leg.getProgressGeometrySegmentIndex() == null
                ? 0 : leg.getProgressGeometrySegmentIndex();
        Duration remaining = calculator.remainingDuration(
                geometry.segments(), segmentIndex, leg.getProgressFraction());
        int remainingMeters = calculator.remainingDistanceMeters(
                geometry.segments(), segmentIndex, leg.getProgressFraction());

        return new CurrentLegArrival(
                calculator.arrivalAt(observedAt, remaining),
                (int) remaining.toSeconds(),
                remainingMeters,
                leg.getRemainingStops(),
                Source.CLIENT_GEOMETRY_PROGRESS,
                worse(Quality.GOOD, geometry.quality()),
                observedAt);
    }

    /**
     * 관측이 멈춘 동안은 마지막으로 고정한 도착 시각을 그대로 쓴다.
     *
     * <p>이미 저장된 {@code predictedEndAt} 이 있으면 그것이 답이다. 없으면 그때도 지금 시각을
     * 기준으로 삼지 않고 마지막 관측 시각에 남은 시간을 더한다.
     */
    private CurrentLegArrival keepLastFixedArrival(JourneyLeg leg, Instant observedAt) {
        Instant fixed = leg.getPredictedEndAt();
        Integer remainingSeconds = leg.getRemainingDurationSeconds();
        if (fixed == null) {
            if (remainingSeconds == null) {
                return fromPlannedDuration(leg);
            }
            fixed = observedAt.plusSeconds(remainingSeconds);
        }
        return new CurrentLegArrival(
                fixed,
                remainingSeconds == null ? 0 : remainingSeconds,
                leg.getRemainingDistanceMeters(),
                leg.getRemainingStops(),
                Source.CLIENT_GEOMETRY_PROGRESS,
                Quality.APPROXIMATE,
                observedAt);
    }

    /**
     * 진행도가 없을 때의 근사. 단계가 시작된 시각에 계획 소요시간을 더한다.
     *
     * <p>단계가 아직 시작되지 않았거나 계획 소요시간조차 없으면 모른다고 말한다. 대기 단계의
     * 소요시간이 여기 해당한다 — 검색 결과에 대기시간이 없어서, 이 값을 만들어 내면 근거 없는
     * 숫자가 된다.
     */
    private CurrentLegArrival fromPlannedDuration(JourneyLeg leg) {
        Integer planned = leg.getPlannedDurationSec();
        Instant startedAt = leg.getActivatedAt();
        if (planned == null || startedAt == null) {
            return CurrentLegArrival.unknown();
        }
        return new CurrentLegArrival(
                startedAt.plusSeconds(planned),
                planned,
                leg.getRemainingDistanceMeters(),
                leg.getRemainingStops(),
                Source.PLANNED_DURATION,
                Quality.APPROXIMATE,
                null);
    }

    private boolean isStale(Instant observedAt, Instant now) {
        return observedAt.isBefore(now.minus(properties.progressFreshness()));
    }

    /** 좌표가 실측이 아니면 ETA 도 그만큼만 믿을 수 있다. */
    private Quality worse(Quality quality, GeometryQuality geometryQuality) {
        if (geometryQuality == null || geometryQuality == GeometryQuality.OK) {
            return quality;
        }
        return Quality.APPROXIMATE;
    }
}
