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
    UNKNOWN;

    /**
     * 나쁜 정도. 클수록 나쁘다. 떨림 방지 판정과 알림 승격 판단이 같은 표를 봐야 하므로 여기서
     * 소유한다.
     *
     * <p>{@code UNKNOWN} 을 가장 나쁘게 두지 않는다. 모르는 것과 놓친 것은 다르고, 모른다는
     * 이유로 "놓쳤다"고 말하면 사용자가 포기해 버린다.
     *
     * <p><b>알림 쪽이 조심할 것</b> — {@code UNKNOWN}(2)이 {@code CATCHABLE}(1) 위다. 숫자만
     * 비교하면 "모르겠다"가 알림이 된다. 알릴 상태인지를 거르는 것이 숫자 비교보다 <b>먼저</b>
     * 와야 한다.
     */
    public int severity() {
        return switch (this) {
            case COMFORTABLE -> 0;
            case CATCHABLE -> 1;
            case UNKNOWN -> 2;
            case AT_RISK -> 3;
            case MISSED -> 4;
        };
    }
}
