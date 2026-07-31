package watoo.grd.nextroute.domain.journey.entity;

/**
 * 환승 연결이 될 것 같은지. 값 목록은 V35 의 {@code transfer_connection_status_check} 가 소유한다.
 *
 * <p>실제 판정은 Phase 3 의 ETA·환승 평가가 채운다. 이번 단계는 자리만 만들고 {@link #UNKNOWN}
 * 으로 저장한다.
 */
public enum TransferStatus {

    /** 여유 있게 갈아탈 수 있음. */
    COMFORTABLE,

    /** 서두르면 됨. */
    CATCHABLE,

    /** 놓칠 가능성이 큼. */
    AT_RISK,

    /** 놓침. */
    MISSED,

    /** 아직 판단할 근거가 없음. 시작 직후의 기본값. */
    UNKNOWN
}
