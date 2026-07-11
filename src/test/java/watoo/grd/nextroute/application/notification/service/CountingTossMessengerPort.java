package watoo.grd.nextroute.application.notification.service;

import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 알림 디스패치/relay IT 공유 fake TossMessengerPort. 발송 호출 수를 센다(옵션 지연으로 처리량 측정).
 */
public class CountingTossMessengerPort implements TossMessengerPort {

    private final AtomicInteger calls = new AtomicInteger();
    private final long latencyMs;

    public CountingTossMessengerPort(long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public int calls() {
        return calls.get();
    }

    public void reset() {
        calls.set(0);
    }

    @Override
    public void sendMessage(long tossUserKey, String templateSetCode, Map<String, Object> context) {
        calls.incrementAndGet();
        CountingBusApiPort.sleep(latencyMs);
    }
}
