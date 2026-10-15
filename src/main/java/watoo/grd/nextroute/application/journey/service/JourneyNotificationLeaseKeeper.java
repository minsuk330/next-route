package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 이 인스턴스가 들고 있는 알림 행의 증서와, 그 행을 살아 있다고 알리는 heartbeat·회수(#451).
 *
 * <p>옛 relay 는 "PROCESSING 으로 3분" 이면 워커가 죽은 것으로 봤다. 배치 200건을 잡아 워커 4개로 보내면 큐 뒤쪽 행이
 * 그 3분에 붙어(#446 ③a), 살아 있는 행을 회수해 두 번 보낼 뻔했다. 이제 살아 있는 행은 주기마다 {@code processing_started_at}
 * 을 DB {@code now()} 로 밀고, 회수는 그 갱신이 끊긴 행만 건진다.
 *
 * <p><b>회수 문.</b> 회수는 이 인스턴스의 직전 heartbeat 성공이 회수 기준/3 안일 때만 한다. DB 가 멈췄다 돌아온 직후엔
 * 다른 인스턴스도 heartbeat 를 못 했으므로, 그때 바로 회수하면 살아 있는 남의 행을 쓸어 담는다. 첫 주기는 늘 닫혀 있다.
 *
 * <p>스레드는 여기서 만들지 않는다. 주기 실행은 relay 루프({@code JourneyNotificationRelayLoop}, worker 프로필)가 한다.
 */
@Slf4j
@Component
public class JourneyNotificationLeaseKeeper {

    private final ConcurrentHashMap<Long, Integer> inFlight = new ConcurrentHashMap<>();
    private final JourneyNotificationOutboxStateService stateService;
    private final JourneyNotificationProperties properties;
    private final JourneyNotificationMetrics metrics;
    private final Clock clock;
    private final LongSupplier nanoTime;

    /** 직전 성공한 주기의 시작 시각(nanoTime). 아직 없으면 회수 문이 닫혀 있다. */
    private volatile long lastSuccessNanos;
    private volatile boolean succeededOnce;

    @Autowired
    public JourneyNotificationLeaseKeeper(JourneyNotificationOutboxStateService stateService,
                                          JourneyNotificationProperties properties,
                                          JourneyNotificationMetrics metrics, Clock clock) {
        this(stateService, properties, metrics, clock, System::nanoTime);
    }

    /** 시험용: 단조 시계를 바꿔 회수 문을 연다·닫는다. */
    JourneyNotificationLeaseKeeper(JourneyNotificationOutboxStateService stateService,
                                   JourneyNotificationProperties properties,
                                   JourneyNotificationMetrics metrics, Clock clock, LongSupplier nanoTime) {
        this.stateService = stateService;
        this.properties = properties;
        this.metrics = metrics;
        this.clock = clock;
        this.nanoTime = nanoTime;
        metrics.registerInflight(inFlight::size);
    }

    /** claim 직후 부른다. 이때부터 heartbeat 대상이다. */
    public void hold(ClaimedNotification claim) {
        inFlight.put(claim.id(), claim.token());
    }

    /** 기록이 끝났거나(성공·늦은 기록 모두) 포기했을 때 부른다. 다른 증서로 다시 잡힌 같은 행은 지우지 않는다. */
    public void release(ClaimedNotification claim) {
        inFlight.remove(claim.id(), claim.token());
    }

    public int inFlightCount() {
        return inFlight.size();
    }

    /**
     * 한 주기: heartbeat 먼저, 그 다음 (문이 열려 있으면) 회수. 한 트랜잭션이다.
     *
     * @return 회수한 행 수. 실패하면 -1(성공 시각을 갱신하지 않아 다음 주기 회수 문이 닫힐 수 있다)
     */
    public int beat() {
        long start = nanoTime.getAsLong();
        boolean reclaimAllowed = succeededOnce
                && start - lastSuccessNanos <= properties.claimTimeout().dividedBy(3).toNanos();
        try {
            int reclaimed = stateService.heartbeatAndReclaim(Map.copyOf(inFlight), reclaimAllowed,
                    Instant.now(clock));
            lastSuccessNanos = start;
            succeededOnce = true;
            if (reclaimed > 0) {
                log.warn("[JourneyNotificationRelay] heartbeat 가 끊긴 행 {}건 회수", reclaimed);
            }
            return reclaimed;
        } catch (RuntimeException e) {
            log.warn("[JourneyNotificationRelay] heartbeat 실패: {}", e.getMessage());
            return -1;
        } finally {
            metrics.recordHeartbeat(Duration.ofNanos(nanoTime.getAsLong() - start));
        }
    }
}
