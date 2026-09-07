package watoo.grd.nextroute.application.route.routing;

/**
 * 이 leg 가 실시간/모델 보강을 받을 <b>능력</b>이 있는지(actualSource 와 별개).
 * 능력이 있어도 이번엔 정적으로 fallback 할 수 있다.
 */
public enum RealtimeCoverage {
    /** 수도권 밖 등 지원 불가. */
    NONE,
    /** 수도권 내이나 provider ID 매핑이 없어 정적 시간표만. */
    STATIC_SCHEDULE,
    /** 서울 실시간 도착 매핑됨. */
    REALTIME_CAPABLE,
    /** ML 승차예측 지원 노선. */
    MODEL_CAPABLE
}
