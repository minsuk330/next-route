package watoo.grd.nextroute.domain.notification.entity;

/**
 * 추천 출발시각 신뢰도. evaluator 가 경로 구간의 예측 소스 품질로 판정한다.
 */
public enum RouteAlertReliability {
    /** 모든 버스 구간 REALTIME/MODEL AVAILABLE + 지하철 근사 없음. */
    HIGH,
    /** 일부 근사(ARRIVAL_TIME_APPROXIMATE) 또는 일부 fallback(term/2). */
    MEDIUM,
    /** fallback 다수·NO_VEHICLE·resolve 실패·스냅샷 stale 등. */
    LOW
}
