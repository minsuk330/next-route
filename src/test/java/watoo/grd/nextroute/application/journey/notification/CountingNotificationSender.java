package watoo.grd.nextroute.application.journey.notification;

import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 발송을 세기만 하는 가짜 sender. {@code CountingTossMessengerPort} 와 같은 모양이다.
 *
 * <p>실패를 시나리오로 심을 수 있다 — 일시 실패·영구 실패는 물론, 계약에 없는 예외(NPE)도
 * 던질 수 있어야 한다. relay 가 그것까지 잡지 못하면 행이 PROCESSING 에 갇혀 영원히 재시도한다.
 */
class CountingNotificationSender implements NotificationSender {

    private final List<NotificationSendCommand> sent = new ArrayList<>();
    private final Map<NotificationChannel, Integer> byChannel = new EnumMap<>(NotificationChannel.class);

    /** 매 호출마다 먼저 돈다. 예외를 던지면 그것이 발송 실패다. */
    private volatile Consumer<NotificationSendCommand> behavior = command -> {
    };

    @Override
    public synchronized void send(NotificationSendCommand command) {
        behavior.accept(command);
        sent.add(command);
        byChannel.merge(command.channel(), 1, Integer::sum);
    }

    synchronized List<NotificationSendCommand> sent() {
        return List.copyOf(sent);
    }

    synchronized int count() {
        return sent.size();
    }

    synchronized int countOf(NotificationChannel channel) {
        return byChannel.getOrDefault(channel, 0);
    }

    synchronized void reset() {
        sent.clear();
        byChannel.clear();
        behavior = command -> {
        };
    }

    void failTransiently(String reason) {
        behavior = command -> {
            throw new NotificationSendException(reason, false);
        };
    }

    void failPermanently(String reason) {
        behavior = command -> {
            throw new NotificationSendException(reason, true);
        };
    }

    /** 보내지 않기로 함(#428 조용한 갱신을 못 받는 기기 등). */
    void skip(String reason) {
        behavior = command -> {
            throw new watoo.grd.nextroute.application.journey.exception.NotificationSkippedException(reason);
        };
    }

    /** 계약에 없는 예외. relay 가 {@code Throwable} 까지 잡는지 보는 데 쓴다. */
    void throwUnexpected() {
        behavior = command -> {
            throw new NullPointerException("sender 내부 오류");
        };
    }
}
