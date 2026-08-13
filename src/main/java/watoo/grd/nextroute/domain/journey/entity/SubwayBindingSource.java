package watoo.grd.nextroute.domain.journey.entity;

/**
 * 이 고정이 무엇을 근거로 생겼는지. 값 목록은 V44 의
 * {@code subway_leg_binding_source_check} 가 소유한다.
 *
 * <p>판정 품질을 나중에 재려면 근거를 갈라 둬야 한다. 센서가 확실히 차량 이동이라고 말한 것과
 * 애매한 것을 뭉치면, 어느 쪽에서 오판이 나는지 영영 모른다.
 */
public enum SubwayBindingSource {

    /** 기기가 차량 이동으로 분류했다. */
    MOTION_AUTOMOTIVE,

    /** 차량 이동으로 분류되지는 않았지만 걷지도 서 있지도 않았다. */
    MOTION_UNKNOWN,

    /** 사용자가 직접 알렸다. 자동 판정을 되돌릴 수 있는 유일한 근거다. */
    USER_MANUAL,

    /** 재탐색으로 개정본이 올랐지만 지하철 구간이 그대로여서 이어받았다. */
    INHERITED_ON_REROUTE
}
