package watoo.grd.nextroute.application.journey.exception;

import lombok.Getter;

/**
 * 증서(claim 때의 {@code attempt_count})가 더는 맞지 않아 상태를 못 바꿨다(#451).
 *
 * <p>회수돼 다른 워커가 다시 잡았거나, 이미 다른 경로로 끝난 행에 늦은 기록이 도착한 것이다. 같은 트랜잭션에서 한 울릴
 * 권리 반납까지 되돌리려고 <b>던진다</b> — 조용히 0행으로 넘어가면 권리만 비워져 같은 경로 버전이 두 번 울린다.
 * relay 가 잡아 {@code journey.notification.stale-write{op}} 로 센다.
 */
@Getter
public class StaleClaimException extends RuntimeException {

    /** 지표 tag: {@code sent}·{@code transient}·{@code permanent}·{@code skipped}·{@code cancel}. */
    private final String op;

    public StaleClaimException(String op, long notificationId, int token) {
        super("stale claim: op=" + op + " id=" + notificationId + " token=" + token);
        this.op = op;
    }
}
