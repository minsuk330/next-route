package watoo.grd.nextroute.domain.journey.entity;

/**
 * 진행 링 push 한 건의 결말.
 *
 * <p>여정 알림({@code JourneyNotificationStatus})의 여섯 상태보다 짧다. 그쪽은 at-least-once 라
 * 재시도 사다리와 사망 판정이 필요하지만, 링 갱신은 latest-wins 여서 <b>구형이 되는 것</b>이
 * 실패만큼이나 흔한 정상 결말이기 때문이다.
 */
public enum SubwayRingPushStatus {

    /** 발송 대기. 여정·개정본·단계마다 살아 있는 것은 하나뿐이다(부분 unique). */
    PENDING,

    /**
     * 워커가 집어 갔다.
     *
     * <p>이 상태로 옮기는 순간 그 행은 부분 unique 의 술어에서 <b>빠진다</b> — 그래서 갱신 tick 이
     * 새 {@code PENDING} 을 넣을 수 있고, 그 창은 발송 직전 최신성 재확인이 막는다.
     */
    SENDING,

    /** 나갔다. 이미 나간 것은 되돌릴 수 없으므로 순서 문제는 {@code aps.timestamp} 에 맡긴다. */
    SENT,

    /**
     * 더 새로운 것이 생겨 폐기했다. <b>실패가 아니다.</b>
     *
     * <p>링 갱신에서 가장 흔한 결말이고, 이 수가 많다는 것은 오히려 원천이 살아 있다는 뜻이다.
     */
    SKIPPED,

    /** 재시도 상한을 넘겼거나 유효기간이 지났다. */
    FAILED
}
