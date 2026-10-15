package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정 알림의 발송 우선순위(#451 PR-2). {@code journey_notification.priority} 에 적재 때 한 번 찍는다.
 *
 * <p>값이 작을수록 먼저 나간다. 1차 기준은 <b>체크포인트</b>다 — 사용자의 다음 행동이 얼마나 급한가.
 * 재평가 등급({@code DetectionStamp.tier}, payload 의 {@code seenTier})은 쓰지 않는다. 그것은 "얼마나 자주 다시 볼까"이지
 * "이 알림이 얼마나 급한가"가 아니다(플랜 Opus 6).
 *
 * <p>모든 적재 호출부가 이 클래스를 거친다: 체크포인트 알림은 {@link #of(JourneyCheckpoint)}, 관리자 시험 알림은
 * {@link #ADMIN_TEST}. 보드 push 는 체크포인트가 {@code REROUTE_SUGGESTED} 라 {@code of} 로 2 가 된다.
 */
public enum NotificationPriority {

    /** 다음 행동이 급함 — 환승 놓침(MISSED)·빠듯(AT_RISK). */
    URGENT(0),
    /** 곧 — 갈아탈 차 접근(N2). */
    SOON(1),
    /** 여유 — 재탐색 제안·보드 push·관리자 시험. 칼럼 기본값과 같다. */
    RELAXED(2);

    /** 관리자 시험 알림. 어떤 체크포인트로 시험하든 운영 알림을 앞지르지 않게 늘 여유다. */
    public static final NotificationPriority ADMIN_TEST = RELAXED;

    private final short value;

    NotificationPriority(int value) {
        this.value = (short) value;
    }

    /** DB 칼럼 값. */
    public short value() {
        return value;
    }

    /** 체크포인트 → 우선순위. switch 가 enum 전부를 다뤄 새 체크포인트가 생기면 컴파일이 깨진다. */
    public static NotificationPriority of(JourneyCheckpoint checkpoint) {
        return switch (checkpoint) {
            case TRANSFER_RISK_MISSED, TRANSFER_RISK_AT_RISK -> URGENT;
            case TRANSFER_TARGET_APPROACHING -> SOON;
            case REROUTE_SUGGESTED -> RELAXED;
        };
    }
}
