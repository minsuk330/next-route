package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 부하 실험(#427) 전용 가짜 발송처. FCM 대신 <b>지연만 흉내 내고 성공</b>으로 돌려준다.
 *
 * <p>왜 있나: relay 를 켜야 알림 적재 → 발송 구간을 잴 수 있는데, 켜면 {@code JourneyRelaySenderGuard} 가
 * no-op 발송처를 거부하고, 실제 발송처는 FCM 뿐이다. 그래서 relay 가 실제로 도는 상태에서 <b>받은 것을 세는</b>
 * 발송처가 필요하다.
 *
 * <h2>운영에 섞이지 않게 두 겹으로 묶는다</h2>
 * <ul>
 *   <li>{@code loadtest} 프로필 <b>그리고</b> {@code journey.notification.sender=loadtest} 둘 다여야 뜬다.
 *       프로필 없이 sender 만 loadtest 면 발송처 빈이 하나도 없어(no-op 은 값이 없을 때만 뜬다) 부팅이 죽는다
 *       — 조용히 안 보내는 것보다 낫다</li>
 *   <li>{@code prod} 프로필과 함께 켜지면 부팅을 막는다</li>
 * </ul>
 *
 * <h2>FCM 과 같은 비용을 낸다</h2>
 * FCM 발송처처럼 사용자의 가장 최근 push 토큰을 DB 에서 읽고(없으면 같은 영구 실패), 같은 메시지 조립
 * ({@link FcmMessageFactory})을 거친다. 공급자 왕복만 로그정규 지연(평균 {@code loadtest.push.mean-ms},
 * 기본 60ms)으로 바꾼다.
 *
 * <h2>센다 — 불변식 확인용</h2>
 * <ul>
 *   <li>{@code loadtest.push.received{checkpoint,delivery}} — 받은 수</li>
 *   <li>{@code loadtest.push.duplicate.id} — 같은 알림 행 번호를 두 번 받음(최소 한 번 보장의 재발송)</li>
 *   <li>{@code loadtest.push.duplicate.business} — 업무 키(여정·경로 버전·종류·환승·평가 번호·울림 종류)가 같은
 *       것을 두 번 받음. 두 worker 가 같은 사건에 행을 따로 만들면 번호는 달라 위 지표로는 안 잡힌다</li>
 *   <li>{@code loadtest.push.alerting.per_revision_over1} — 피로 정책 대상(위험·재탐색)의 울림을 한 경로 버전에
 *       두 번 이상 받은 (여정, 경로 버전) 수. 정책상 0 이어야 한다</li>
 * </ul>
 */
@Slf4j
@Component
@Profile("loadtest")
@ConditionalOnProperty(prefix = "journey.notification", name = "sender", havingValue = "loadtest")
public class LoadTestNotificationSender implements NotificationSender {

    static final String RECEIVED = "loadtest.push.received";
    static final String DUPLICATE_ID = "loadtest.push.duplicate.id";
    static final String DUPLICATE_BUSINESS = "loadtest.push.duplicate.business";
    static final String ALERTING_OVER_ONE = "loadtest.push.alerting.per_revision_over1";
    static final String SEND = "loadtest.push.send";

    /** 피로 정책이 울림을 경로 버전당 한 번으로 묶는 대상. 접근 알림은 안내라 빠진다. */
    private static final Set<JourneyCheckpoint> POLICY_CHECKPOINTS = Set.of(
            JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED,
            JourneyCheckpoint.REROUTE_SUGGESTED);

    private final UserPushTokenRepository tokenRepository;
    private final FcmMessageFactory messageFactory;
    private final MeterRegistry registry;
    private final Environment environment;
    private final Clock clock;
    private final double meanMillis;

    private final Map<Long, AtomicInteger> byNotificationId = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> byBusinessKey = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> alertingByRevision = new ConcurrentHashMap<>();
    private final AtomicLong revisionsOverOne = new AtomicLong();

    public LoadTestNotificationSender(UserPushTokenRepository tokenRepository,
                                      JourneyNotificationProperties properties,
                                      MeterRegistry registry,
                                      Environment environment,
                                      Clock clock) {
        this.tokenRepository = tokenRepository;
        // FCM 쪽 조립기는 sender=fcm 일 때만 빈이라 여기서 직접 만든다(설정만 받는 순수 조립기).
        this.messageFactory = new FcmMessageFactory(properties);
        this.registry = registry;
        this.environment = environment;
        this.clock = clock;
        this.meanMillis = environment.getProperty("loadtest.push.mean-ms", Double.class, 60.0);
    }

    @PostConstruct
    void refuseProduction() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("가짜 발송처(loadtest)는 prod 프로필과 함께 뜰 수 없다 — "
                    + "알림을 받은 척 SENT 로 소모해 실제 발송을 영영 막는다");
        }
        Gauge.builder(ALERTING_OVER_ONE, revisionsOverOne, AtomicLong::doubleValue).register(registry);
        log.warn("[LoadTestSender] 가짜 발송처가 켜졌다 — 알림은 어디에도 나가지 않는다(부하 실험 전용)");
    }

    @Override
    public void send(NotificationSendCommand command) {
        if (tokenRepository.findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                command.userId()).isEmpty()) {
            throw new NotificationSendException("no-active-token", true);
        }
        PushMessage message = messageFactory.from(command, Instant.now(clock));
        long started = System.nanoTime();
        simulateProviderLatency();
        Timer.builder(SEND).register(registry).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        record(command, message.delivery());
    }

    /** 받은 것을 센다. 발송 지연과 떼어 두어 시험이 지연 없이 부른다. */
    void record(NotificationSendCommand command, PushMessage.Delivery delivery) {
        String deliveryTag = delivery == null ? "none" : delivery.name();
        registry.counter(RECEIVED, "checkpoint", command.checkpoint().name(), "delivery", deliveryTag).increment();

        if (byNotificationId.computeIfAbsent(command.notificationId(), id -> new AtomicInteger())
                .incrementAndGet() > 1) {
            registry.counter(DUPLICATE_ID).increment();
        }
        String business = command.journeyId() + "|" + command.routeRevision() + "|" + command.checkpoint()
                + "|" + command.scopeId() + "|" + command.evaluationVersion() + "|" + deliveryTag;
        if (byBusinessKey.computeIfAbsent(business, k -> new AtomicInteger()).incrementAndGet() > 1) {
            registry.counter(DUPLICATE_BUSINESS).increment();
        }
        if (delivery == PushMessage.Delivery.ALERTING && POLICY_CHECKPOINTS.contains(command.checkpoint())) {
            String revision = command.journeyId() + "|" + command.routeRevision();
            if (alertingByRevision.computeIfAbsent(revision, k -> new AtomicInteger()).incrementAndGet() == 2) {
                revisionsOverOne.incrementAndGet();
            }
        }
    }

    long revisionsWithMoreThanOneAlerting() {
        return revisionsOverOne.get();
    }

    /** 운영 FCM 왕복 흉내: 평균이 meanMillis 인 로그정규(σ=0.6). */
    private void simulateProviderLatency() {
        double sigma = 0.6;
        double mu = Math.log(Math.max(meanMillis, 1)) - sigma * sigma / 2;
        double millis = Math.exp(mu + sigma * ThreadLocalRandom.current().nextGaussian());
        try {
            Thread.sleep((long) millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationSendException("interrupted", false);
        }
    }
}
