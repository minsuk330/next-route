package watoo.grd.nextroute.application.route.dto;

import java.time.Instant;

/**
 * "이 버스를 타면 다음 버스 구간에서 얼마나 기다리나" 한 줄(bus-lane-selection).
 *
 * <p>버스 구간에 노선이 여럿 묶이면 노선마다 오는 시각이 달라 다음 환승 정류장에 닿는 시각도 다르다. 앞 구간의 lane 마다
 * ({@link TransferArrival#onwardWaits()}) 다음 버스 구간의 lane 별 대기를 싣는다. 다음 구간의 {@code transferArrivals} 는 가장
 * 먼저 오는 lane 을 탔다고 본 값 하나뿐이다.
 *
 * @param laneIndex          다음 버스 구간의 lane 순번
 * @param routeId            그 lane 의 실시간 노선 id
 * @param userArrivalAt      이 버스를 타고 다음 환승 정류장에 닿는 예상 시각
 * @param predictedArrivalAt 닿은 뒤 처음 탈 수 있는 그 노선 버스의 도착 시각. 실시간(다음 한두 대)에 있으면 그 값, 없으면
 *                           도착 예측 모델 값. <b>null = 모름</b>(둘 다 없음) — "차가 없다"·"대기 0"으로 읽으면 안 된다
 * @param waitSeconds        대기(초). {@code predictedArrivalAt} 이 null 이면 null(모름)
 * @param source             {@code REALTIME}(실시간 도착) 또는 {@code MODEL}(예측 모델, 오차가 더 크다). 모르면 null
 */
public record OnwardWait(
        int laneIndex,
        String routeId,
        Instant userArrivalAt,
        Instant predictedArrivalAt,
        Long waitSeconds,
        TransferArrival.Source source
) {

    /** 출처 칸이 생기기 전 호출부(시험)용. */
    public OnwardWait(int laneIndex, String routeId, Instant userArrivalAt, Instant predictedArrivalAt,
                      Long waitSeconds) {
        this(laneIndex, routeId, userArrivalAt, predictedArrivalAt, waitSeconds, null);
    }
}
