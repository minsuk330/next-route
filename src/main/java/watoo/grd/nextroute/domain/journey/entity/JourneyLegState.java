package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정 한 단계의 진행 상태. 값 목록은 V35의 {@code journey_leg_state_check}가 소유한다.
 *
 * <p>{@link #UNKNOWN}은 "서버가 이 사용자의 현재 leg를 모른다"는 뜻이다. 정류장·노선
 * 식별자가 없는 것은 근거 품질 문제이므로 {@code JourneyLeg.evidenceQuality}에 표시하고,
 * leg는 {@code ACTIVE/PENDING}으로 남겨 GPS progress engine이 경계를 확정할 때까지 유지한다.
 */
public enum JourneyLegState {

    /** 아직 차례가 오지 않음. */
    PENDING,

    /** 지금 이 단계를 지나는 중. 여정당 하나. */
    ACTIVE,

    /** legacy 상태. 1차 GPS 네비게이션은 이 상태나 확인 UI를 생성하지 않는다. */
    CONFIRMATION_REQUIRED,

    /** 지나감. */
    COMPLETED,

    /** 건너뜀. */
    SKIPPED,

    /** 서버가 현재 위치를 판별하지 못함. */
    UNKNOWN
}
