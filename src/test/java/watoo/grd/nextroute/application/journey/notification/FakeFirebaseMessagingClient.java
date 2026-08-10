package watoo.grd.nextroute.application.journey.notification;

import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * SDK 자리에 끼우는 가짜. token 별 결과를 시나리오로 심는다.
 *
 * <p>{@code CountingNotificationSender} 와 같은 모양이다 — 실패를 심을 수 있어야 "죽은 token 이
 * 실제로 꺼지는가"를 DB 로 확인할 수 있다.
 */
class FakeFirebaseMessagingClient implements FirebaseMessagingClient {

    private final Map<String, String> failures = new HashMap<>();
    private final List<String> lastTokens = new ArrayList<>();
    private int callCount;

    /** @param errorCode {@code MessagingErrorCode} 이름. 예: {@code UNREGISTERED} */
    synchronized void fail(String token, String errorCode) {
        failures.put(token, errorCode);
    }

    synchronized void reset() {
        failures.clear();
        lastTokens.clear();
        callCount = 0;
    }

    synchronized List<String> lastTokens() {
        return List.copyOf(lastTokens);
    }

    synchronized int callCount() {
        return callCount;
    }

    @Override
    public synchronized List<PushResult> send(PushMessage message, List<String> tokens) {
        callCount++;
        lastTokens.clear();
        lastTokens.addAll(tokens);
        return tokens.stream()
                .map(token -> failures.containsKey(token)
                        ? PushResult.failed(failures.get(token))
                        : PushResult.ok())
                .toList();
    }
}
