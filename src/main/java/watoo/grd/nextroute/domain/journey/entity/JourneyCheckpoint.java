package watoo.grd.nextroute.domain.journey.entity;

/**
 * 체크포인트 어휘 — "여정에서 무슨 일이 생겨 알리는가".
 *
 * <p>DB 에 CHECK 를 걸지 않고 여기서 소유한다. 값 목록을 늘리는 것은 PR4 인데 PR4 에는
 * 마이그레이션이 없다.
 *
 * <p><b>상태를 이름에 품는 것이 필수다.</b> {@code TRANSFER_RISK} 하나로 두면
 * {@code AT_RISK → MISSED} 승격이 같은 {@code deliveryKey} 를 만들어 {@code ON CONFLICT} 에
 * 삼켜진다 — 가장 중요한 알림이 영영 안 나간다.
 */
public enum JourneyCheckpoint {

    /** 환승 여유가 2분 미만으로 줄었다. */
    TRANSFER_RISK_AT_RISK,

    /** 환승 여유가 음수다 — 이미 놓쳤다. */
    TRANSFER_RISK_MISSED;

    /**
     * 환승 상태에 대응하는 체크포인트.
     *
     * @throws IllegalArgumentException 알릴 상태가 아닌 값(COMFORTABLE·CATCHABLE·UNKNOWN).
     *         알릴지 말지의 판단은 부르는 쪽이 이미 끝냈어야 한다
     */
    public static JourneyCheckpoint ofTransferStatus(TransferStatus status) {
        return switch (status) {
            case AT_RISK -> TRANSFER_RISK_AT_RISK;
            case MISSED -> TRANSFER_RISK_MISSED;
            default -> throw new IllegalArgumentException("알림 대상이 아닌 환승 상태: " + status);
        };
    }
}
