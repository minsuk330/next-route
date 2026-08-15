package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.subway.dto.SubwayArrivalCode;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;

import java.time.Instant;

/**
 * 링을 한 번 옮기는 <b>실시간 관측 한 건</b>. 두 원천을 하나의 모양으로 좁혀 둔 값이다.
 *
 * <h2>왜 원천을 둘 다 남기나</h2>
 *
 * <p>도착 API 만 남은 초를 주지만 <b>전 열차를 커버하지 않는다</b>(실측: 아예 없음 3% + 먼 역만
 * 5%). 역당 방향별 2대 정도만 주기 때문에 우리 열차가 그 역 상위 2대에 못 들면 목록에서
 * 사라진다. 위치 API 는 초를 못 주지만 <b>빠짐없이</b> 준다.
 *
 * <p>그래서 역할을 나눈다 — 초는 도착이, <b>구간 전환은 위치가</b> 받친다. 도착 하나에만
 * 매달리면 그 여정은 내내 링이 죽는다.
 *
 * @param observedStationSequence 이 관측이 가리키는 역의 체인 순번.
 *                                도착은 <b>조회한 역</b>, 위치는 열차의 <b>현재 역</b>이다
 * @param estimatedArrivalAt      도착 전용. {@code recptnDt + barvlDt} 의 절대 시각. 없으면 null.
 *                                남은 초를 그대로 들지 않는 이유는 응답의 {@code recptnDt} 가
 *                                <b>행마다 다르고</b> 이미 1~2분 뒤처져 있기 때문이다
 * @param observedAt              도착은 <b>그 행의</b> {@code recptnDt}, 위치는 관측 수신 시각
 */
public record SubwayRingObservation(
        Source source,
        SubwayArrivalCode code,
        SubwayTrainStatus positionStatus,
        int observedStationSequence,
        Instant estimatedArrivalAt,
        Instant observedAt
) {

    public enum Source {
        /** 도착 API. 남은 초의 유일한 원천이다. */
        ARRIVAL,
        /** 위치 API. 초는 없지만 전 열차를 커버한다 — 구간 전환의 안전망. */
        POSITION
    }

    public static SubwayRingObservation arrival(SubwayArrivalCode code, int stationSequence,
                                                Instant estimatedArrivalAt, Instant observedAt) {
        return new SubwayRingObservation(Source.ARRIVAL, code, null,
                stationSequence, estimatedArrivalAt, observedAt);
    }

    public static SubwayRingObservation position(SubwayTrainStatus status, int stationSequence,
                                                 Instant observedAt) {
        return new SubwayRingObservation(Source.POSITION, null, status,
                stationSequence, null, observedAt);
    }
}
