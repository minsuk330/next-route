package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정 한 단계의 진행 상태. 값 목록은 V35 의 {@code journey_leg_state_check} 가 소유한다.
 *
 * <p>{@link #UNKNOWN} 은 "서버가 이 사람이 지금 어느 단계인지 모른다"는 뜻이다. 정류장·노선
 * 식별자가 없다는 이유로 이 값을 쓰지 않는다 — 그건 근거의 품질 문제라
 * {@code JourneyLeg.evidenceQuality} 가 표시하고, 단계 자체는 {@code ACTIVE/PENDING} 으로
 * 남겨 사용자가 손으로 진행할 수 있게 둔다.
 */
public enum JourneyLegState {

    /** 아직 차례가 오지 않음. */
    PENDING,

    /** 지금 이 단계를 지나는 중. 여정당 하나. */
    ACTIVE,

    /** 서버 판단과 사용자 상태가 어긋나 확인이 필요함(Phase 2). */
    CONFIRMATION_REQUIRED,

    /** 지나감. */
    COMPLETED,

    /** 건너뜀. */
    SKIPPED,

    /** 서버가 현재 위치를 판별하지 못함. */
    UNKNOWN
}
