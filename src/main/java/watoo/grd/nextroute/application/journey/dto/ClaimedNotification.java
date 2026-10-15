package watoo.grd.nextroute.application.journey.dto;

/**
 * relay 가 집은 알림 한 건과 그 증서(#451).
 *
 * <p>{@code token} 은 claim 이 올린 뒤의 {@code attempt_count} 다. 회수된 행을 누가 다시 잡으면 claim 이 그 값을 또 올리므로
 * 옛 워커의 증서는 맞지 않게 된다. 상태 변경은 전부 {@code status='PROCESSING' AND attempt_count = token} 일 때만 쓴다.
 */
public record ClaimedNotification(long id, int token) {
}
