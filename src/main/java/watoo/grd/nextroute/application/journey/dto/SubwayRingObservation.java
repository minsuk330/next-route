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
 * @param seenAt                  서버가 이 {@code observedAt} 을 처음 본 시각. 갱신기가 직전 링과 견주어 채운다 —
 *                                원천이 같은 행을 계속 주면 처음 본 시각이 이어진다. 모르면 null
 * @param presentAt               서버가 우리 열차 행을 <b>마지막으로 받아 본</b> 시각(도착 스냅샷 수집 시각). STALE 판정용 — 행이
 *                                바뀌지 않아도 수집이 살아 있으면 갱신된다. 위치 관측은 null
 */
public record SubwayRingObservation(
        Source source,
        SubwayArrivalCode code,
        SubwayTrainStatus positionStatus,
        int observedStationSequence,
        Instant estimatedArrivalAt,
        Instant observedAt,
        Instant segmentStartAt,
        Instant seenAt,
        Instant presentAt
) {

    public SubwayRingObservation(Source source, SubwayArrivalCode code, SubwayTrainStatus positionStatus,
                                 int observedStationSequence, Instant estimatedArrivalAt, Instant observedAt,
                                 Instant segmentStartAt, Instant seenAt) {
        this(source, code, positionStatus, observedStationSequence, estimatedArrivalAt, observedAt, segmentStartAt,
                seenAt, null);
    }

    public SubwayRingObservation(Source source, SubwayArrivalCode code, SubwayTrainStatus positionStatus,
                                 int observedStationSequence, Instant estimatedArrivalAt, Instant observedAt,
                                 Instant segmentStartAt) {
        this(source, code, positionStatus, observedStationSequence, estimatedArrivalAt, observedAt, segmentStartAt,
                null);
    }

    public SubwayRingObservation(Source source, SubwayArrivalCode code, SubwayTrainStatus positionStatus,
                                 int observedStationSequence, Instant estimatedArrivalAt, Instant observedAt) {
        this(source, code, positionStatus, observedStationSequence, estimatedArrivalAt, observedAt, null);
    }

    /** 서버가 이 관측을 처음 본 시각을 붙인다. */
    public SubwayRingObservation withSeenAt(Instant seen) {
        return new SubwayRingObservation(source, code, positionStatus, observedStationSequence, estimatedArrivalAt,
                observedAt, segmentStartAt, seen, presentAt);
    }

    /** 서버가 우리 열차 행을 마지막으로 받아 본 시각을 붙인다(도착 스냅샷 수집 시각). */
    public SubwayRingObservation withPresentAt(Instant present) {
        return new SubwayRingObservation(source, code, positionStatus, observedStationSequence, estimatedArrivalAt,
                observedAt, segmentStartAt, seenAt, present);
    }

    /** 전역 출발 실측의 구간 시간(예정 − 출발). 실측이 아니면 null. */
    public java.time.Duration measuredDuration() {
        return isMeasured() ? java.time.Duration.between(segmentStartAt, estimatedArrivalAt) : null;
    }

    /**
     * 전역 출발(3) 구간의 도착 예정을 <b>앞 열차 실측</b>으로 만든 관측(2026-10-07 결정).
     *
     * @param segmentStartAt 이 열차가 전역을 출발한 것으로 처음 보인 시각 — 링 앵커의 기준 시각
     */
    public static SubwayRingObservation measured(int stationSequence, Instant segmentStartAt,
                                                 Instant estimatedArrivalAt, Instant observedAt) {
        return new SubwayRingObservation(Source.ARRIVAL, SubwayArrivalCode.PREV_DEPARTED, null,
                stationSequence, estimatedArrivalAt, observedAt, segmentStartAt);
    }

    /** 도착 예정이 앞 열차 실측에서 왔는가. */
    public boolean isMeasured() {
        return segmentStartAt != null && estimatedArrivalAt != null;
    }

    public enum Source {
        /** 도착 API. 남은 초의 유일한 원천이다. */
        ARRIVAL,
        /** 위치 API. 초는 없지만 전 열차를 커버한다 — 구간 전환의 안전망. */
        POSITION,
        /**
         * 열차가 이미 우리 구간(승차역~하차역)을 지나갔다(#311). 위치 로그의 역별 도착 이력에 구간 역이 있고 지금은 구간
         * 밖이다 — 하차역 너머로 갔다. 링은 하차역 도착으로 마무리한다.
         */
        PASSED
    }

    public static SubwayRingObservation arrival(SubwayArrivalCode code, int stationSequence,
                                                Instant estimatedArrivalAt, Instant observedAt) {
        return new SubwayRingObservation(Source.ARRIVAL, code, null,
                stationSequence, estimatedArrivalAt, observedAt);
    }

    /** 이미 구간을 지나간 열차 — {@code alightingSequence} 도착으로 마무리한다. */
    public static SubwayRingObservation passed(int alightingSequence, Instant observedAt) {
        return new SubwayRingObservation(Source.PASSED, null, SubwayTrainStatus.ARRIVED,
                alightingSequence, null, observedAt);
    }

    public static SubwayRingObservation position(SubwayTrainStatus status, int stationSequence,
                                                 Instant observedAt) {
        return new SubwayRingObservation(Source.POSITION, null, status,
                stationSequence, null, observedAt);
    }
}
