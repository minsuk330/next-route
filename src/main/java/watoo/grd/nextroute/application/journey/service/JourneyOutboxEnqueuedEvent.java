package watoo.grd.nextroute.application.journey.service;

/**
 * 여정 알림 outbox 에 무언가 쌓였음을 알린다. 커밋 뒤에 relay 를 즉시 깨우는 데만 쓴다.
 *
 * <p>버스 쪽 {@code OutboxEnqueuedEvent} 와 <b>별개 타입</b>이다. 같은 타입을 쓰면 여정 enqueue
 * 하나가 버스 relay 루프까지 깨운다 — 여정은 초당 수백 건이라 그 낭비가 크다.
 *
 * <p>행 번호를 싣지 않는다. 한 번의 평가가 여러 행을 만들 수 있고 깨우는 쪽은 "뭔가 생겼다"만
 * 알면 되기 때문이다.
 */
public record JourneyOutboxEnqueuedEvent() {
}
