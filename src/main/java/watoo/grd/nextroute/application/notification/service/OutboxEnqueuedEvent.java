package watoo.grd.nextroute.application.notification.service;

/**
 * outbox 에 발송 작업이 적재됐음을 알리는 도메인 이벤트. enqueue 성공 시 발행되고,
 * 커밋 후 {@link RelayWakeListener} 가 relay 를 즉시 깨운다. 발행 주체 = outbox producer(누구든).
 */
public record OutboxEnqueuedEvent(long notificationId) {
}
