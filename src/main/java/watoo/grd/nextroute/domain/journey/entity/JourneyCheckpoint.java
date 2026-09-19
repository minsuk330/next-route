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
 *
 * <p><b>하차·정류장 접근은 이 어휘에 없다.</b> 그 둘(N1·N3)은 모바일 local notification 소유라
 * 서버가 보낼 수단 자체를 두지 않는다. "하차 유형 발송 0" 이 규칙이 아니라 타입으로 지켜진다.
 */
public enum JourneyCheckpoint {

    /** 환승 여유가 2분 미만으로 줄었다. */
    TRANSFER_RISK_AT_RISK,

    /** 환승 여유가 음수다 — 이미 놓쳤다. */
    TRANSFER_RISK_MISSED,

    /**
     * 갈아탈 차가 곧 온다. 위험이 아니라 <b>행동 신호</b>다 — 안전한 상태에서만 나간다.
     *
     * <p>상태에서 파생되지 않는다. 상태·남은 시간·현재 단계의 곱이 조건이라
     * {@link #ofTransferStatus} 가 이 값을 만들지 않는다.
     */
    TRANSFER_TARGET_APPROACHING,

    /**
     * 더 빠른 경로를 찾았다(journey-reroute-nextstop PR-3).
     *
     * <p><b>한 줄 신호다.</b> 무엇이 얼마나 빨라지는지는 push 에 싣지 않는다 — 제안은 5분짜리
     * 스냅샷이라 늦게 도착한 알림의 숫자는 이미 틀린 값이고, 앱은 식별자로 최신 목록을 다시
     * 받는다({@code FcmMessageFactory} 의 "push 는 상태 저장소가 아니다").
     *
     * <p>범위는 {@link JourneyNotificationScope#JOURNEY} 다. 제안은 여정당 하나이고, 가리키는
     * 자리는 환승이 아니라 <b>하차 정류장</b>일 수 있어 환승 번호로는 주소가 안 된다.
     */
    REROUTE_SUGGESTED;

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
