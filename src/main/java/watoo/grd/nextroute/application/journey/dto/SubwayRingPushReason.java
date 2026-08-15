package watoo.grd.nextroute.application.journey.dto;

/**
 * 왜 원격 갱신을 보내나. 지표 tag 이자 {@code critical} 판정의 근거다.
 *
 * <p><b>스냅샷을 새로 만들었다는 이유만으로는 보내지 않는다.</b> 링은 5초마다 도는데 대부분의
 * tick 은 화면을 바꾸지 않고, 매번 보내면 잠금화면 갱신이 배터리와 APNs 예산을 함께 먹는다.
 */
public enum SubwayRingPushReason {

    /** 다음 역이 바뀌었다. 사용자가 화면에서 가장 먼저 읽는 값이다. */
    NEXT_STATION(true),

    /** 하차 국면으로 넘어갔다. 내릴 준비를 하라는 말이라 늦으면 안 된다. */
    PHASE(true),

    /** 신선도가 뒤집혔다. "정보 없음"으로 바뀐 것도, 되살아난 것도 알려야 한다. */
    QUALITY(true),

    /** 다음 역에 실제로 도착했다. */
    ARRIVED(true),

    /** Activity 종료. 유실되면 잠금화면에 죽은 안내가 남는다. */
    END(true),

    /**
     * 앵커가 의미 있게 바뀌었다.
     *
     * <p>유일하게 {@code critical} 이 아니다 — 이걸 놓쳐도 앱은 직전 앵커로 계속 보간하고,
     * 다음 갱신이 곧 따라온다.
     */
    ANCHOR(false);

    private final boolean critical;

    SubwayRingPushReason(boolean critical) {
        this.critical = critical;
    }

    /** 최신인 동안 반드시 재시도할 이벤트인지. */
    public boolean isCritical() {
        return critical;
    }

    public String tag() {
        return name().toLowerCase();
    }
}
