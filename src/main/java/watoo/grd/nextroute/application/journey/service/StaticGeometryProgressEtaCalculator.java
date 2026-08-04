package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 정적 geometry 위의 진행도로 남은 시간을 구한다.
 *
 * <p>이동 단계 네 종류(버스·지하철·걷기·환승걷기)가 <b>같은 계산기</b>를 쓴다. 교통수단별로 다른
 * 식을 두지 않는 이유는 입력이 같기 때문이다 — 구간별 거리와 배분된 시간, 그리고 지금 몇 번째
 * 구간 몇 %인지. 실시간 차량 위치나 열차 번호는 쓰지 않는다.
 *
 * <pre>
 * remainingDuration = 현재 구간의 남은 배분시간 + 이후 구간 배분시간 합
 * arrivalAt         = observedAt + remainingDuration
 * </pre>
 *
 * <p>저장소를 보지 않고 아무것도 저장하지 않는다.
 */
@Component
public class StaticGeometryProgressEtaCalculator {

    /**
     * 남은 시간.
     *
     * <p>현재 구간을 반만 지났으면 그 구간 배분시간의 절반만 남은 것으로 센다. 구간 단위로만
     * 세면 정류장 하나를 지날 때마다 남은 시간이 뚝뚝 떨어진다.
     *
     * @param segments        단계의 구간들. 배분시간이 없는 구간은 0초로 센다
     * @param segmentIndex    지금 있는 구간 순번. 범위를 벗어나면 앞뒤로 잘라 맞춘다
     * @param segmentProgress 그 구간 안에서의 진행률 0~1. null 이면 0으로 본다
     */
    public Duration remainingDuration(List<SegmentGeometry> segments,
                                      int segmentIndex, BigDecimal segmentProgress) {
        if (segments == null || segments.isEmpty()) {
            return Duration.ZERO;
        }
        int index = clamp(segmentIndex, segments.size());
        double fraction = fraction(segmentProgress);

        long seconds = Math.round(secondsOf(segments.get(index)) * (1.0 - fraction));
        for (int i = index + 1; i < segments.size(); i++) {
            seconds += secondsOf(segments.get(i));
        }
        return Duration.ofSeconds(Math.max(0, seconds));
    }

    /**
     * 남은 거리. 같은 규칙으로 현재 구간을 비례 배분한다.
     *
     * <p>앱이 보낸 남은 거리를 쓰지 않고 다시 세는 이유는, 이 값이 서버가 아는 좌표와 앱이 보낸
     * 진행도만으로 유도되는 값이라 둘이 어긋나면 좌표 쪽을 기준으로 삼는 편이 낫기 때문이다.
     */
    public int remainingDistanceMeters(List<SegmentGeometry> segments,
                                       int segmentIndex, BigDecimal segmentProgress) {
        if (segments == null || segments.isEmpty()) {
            return 0;
        }
        int index = clamp(segmentIndex, segments.size());
        double fraction = fraction(segmentProgress);

        double meters = segments.get(index).distanceMeters() * (1.0 - fraction);
        for (int i = index + 1; i < segments.size(); i++) {
            meters += segments.get(i).distanceMeters();
        }
        return (int) Math.max(0, Math.round(meters));
    }

    /**
     * 절대 도착 시각.
     *
     * <p><b>관측 시각에 더한다.</b> 서버의 지금 시각에 더하면 안 된다 — 그렇게 하면 GPS 가 끊겨
     * 관측이 갱신되지 않는 동안에도 매 조회마다 도착 시각이 뒤로 밀린다. 사용자 화면의 남은
     * 시간이 줄지 않고 제자리걸음하는 것이 그 증상이다.
     */
    public Instant arrivalAt(Instant observedAt, Duration remainingDuration) {
        return observedAt.plus(remainingDuration);
    }

    private long secondsOf(SegmentGeometry segment) {
        Integer seconds = segment.plannedDurationSeconds();
        return seconds == null ? 0L : Math.max(0, seconds);
    }

    /** 진행률은 0~1 밖으로 나갈 수 없다. 앱이 잘못 보내도 계산이 음수로 뒤집히지 않게 한다. */
    private double fraction(BigDecimal segmentProgress) {
        if (segmentProgress == null) {
            return 0.0;
        }
        return Math.min(1.0, Math.max(0.0, segmentProgress.doubleValue()));
    }

    private int clamp(int index, int size) {
        return Math.min(Math.max(index, 0), size - 1);
    }
}
