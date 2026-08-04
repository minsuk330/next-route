package watoo.grd.nextroute.application.journey.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * 앱이 확정한 진행 요약. 명령이 아니라 관측이다.
 *
 * <p>서버는 이 값을 다시 판정하지 않는다. GPS 튐을 걸러 내고 어느 구간 몇 %인지 정하는 일은
 * 앱의 네이티브 위치 엔진이 이미 했다. 서버가 같은 판단을 또 하면 두 곳의 규칙이 어긋나는
 * 순간부터 사용자가 보는 값과 서버가 아는 값이 달라진다.
 *
 * <p>정밀 좌표는 들어오지 않는다. 남은 거리·시간·정류장 수와 어느 구간인지만 받는다.
 *
 * @param progressId              이 progress 의 식별자. 재전송이면 같은 값으로 다시 보낸다
 * @param routeRevision           앱이 보고 있던 경로 개정본
 * @param legIndex                진행 중인 단계 순번
 * @param geometrySegmentIndex    단계 안에서의 구간 순번
 * @param segmentProgress         현재 구간 진행률 0~1
 * @param legProgress             단계 전체 진행률 0~1
 * @param sectionId               버스 구간 식별자. 버스가 아니면 null
 * @param remainingDistanceMeters 단계 끝까지 남은 거리(m)
 * @param remainingDurationSeconds 단계 끝까지 남은 시간(초)
 * @param remainingStops          남은 정류장 수. 버스가 아니면 null
 * @param observedAt              앱이 이 진행을 확정한 시각. 순서 판정의 기준이다
 * @param quality                 관측 품질
 */
public record JourneyProgressCommand(
        UUID progressId,
        int routeRevision,
        int legIndex,
        Integer geometrySegmentIndex,
        BigDecimal segmentProgress,
        BigDecimal legProgress,
        String sectionId,
        Integer remainingDistanceMeters,
        Integer remainingDurationSeconds,
        Integer remainingStops,
        Instant observedAt,
        String quality
) {

    /** 진행 근거가 앱 GPS 라는 표시. 서버 계산 결과와 구분해 남긴다. */
    public static final String EVIDENCE_SOURCE = "CLIENT_GEOMETRY_PROGRESS";
}
