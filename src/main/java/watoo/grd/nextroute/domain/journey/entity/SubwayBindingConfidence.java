package watoo.grd.nextroute.domain.journey.entity;

/**
 * 기기가 자기 관측을 얼마나 믿는지. 값 목록은 V44 의
 * {@code subway_leg_binding_confidence_check} 가 소유한다.
 *
 * <p>기기가 준 값을 그대로 옮긴다. 서버가 다시 계산하지 않는다 — 센서 신뢰도는 기기만 안다.
 */
public enum SubwayBindingConfidence {
    LOW,
    MEDIUM,
    HIGH
}
