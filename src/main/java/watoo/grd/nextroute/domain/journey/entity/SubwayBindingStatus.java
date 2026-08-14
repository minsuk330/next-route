package watoo.grd.nextroute.domain.journey.entity;

/**
 * 지하철 단계가 어느 열차에 묶였는지의 상태. 값 목록은 V44 의
 * {@code subway_leg_binding_status_check} 가 소유한다.
 *
 * <p><b>{@code CORRECTED} 가 없다.</b> 교정은 {@link #CONFIRMED} 로 끝나고, 교정이 있었다는
 * 사실은 감사 표에만 남는다. 지속 상태로 두면 한 트랜잭션 안에서만 존재하는 유령 상태가 되어
 * DB 에서는 관측되지 않는다.
 */
public enum SubwayBindingStatus {

    /** 이동 시작은 받았지만 아직 열차를 고르지 못했다. */
    PENDING,

    /**
     * 후보를 골랐지만 확정 전이다.
     *
     * <p>이 상태에서는 <b>하차 관련 안내를 내보내지 않는다.</b> 앞차로 보이면 일찍 내리고
     * 뒤차로 보이면 지나치므로 안전한 쪽이 없다.
     */
    PROVISIONAL,

    /** 다음 역 정차까지 맞아떨어졌다. 이제 하차 안내를 낼 수 있다. */
    CONFIRMED,

    /** 시간이 지났거나, 사용자가 타지 않았다고 알렸다. */
    ABANDONED;

    /** 아직 판정이 끝나지 않았는지. 판정기가 훑는 대상이다. */
    public boolean isPending() {
        return this == PENDING || this == PROVISIONAL;
    }

    /** 하차 안내를 낼 수 있는 상태인지. */
    public boolean allowsAlightGuidance() {
        return this == CONFIRMED;
    }
}
