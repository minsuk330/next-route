package watoo.grd.nextroute.application.subway.dto;

import java.time.Instant;

/**
 * 열차 하나의 <b>직전 관측</b>. 전이 감지의 좌변이다.
 *
 * <p>스냅샷은 덮어쓰기라 "지금 어디 있나"만 남는다. 전이는 두 시점을 비교해야 보이므로,
 * 직전 관측을 따로 들고 있어야 한다.
 *
 * @param receivedAt 원천이 말한 {@code recptnDt}. <b>사건 발생 시각이 아니라 최종수신시간</b>이다
 */
public record SubwayTrainObservation(
        String stationId,
        String stationName,
        SubwayTrainStatus status,
        Instant receivedAt,
        String terminalId,
        SubwayExpressType express
) {

    public boolean isSameStation(SubwayTrainObservation other) {
        return other != null && stationId != null && stationId.equals(other.stationId());
    }

    /** 이 관측이 저것보다 새로운가. 같거나 과거면 원천이 아직 갱신되지 않은 것이다. */
    public boolean isNewerThan(SubwayTrainObservation other) {
        return other == null || receivedAt.isAfter(other.receivedAt());
    }
}
