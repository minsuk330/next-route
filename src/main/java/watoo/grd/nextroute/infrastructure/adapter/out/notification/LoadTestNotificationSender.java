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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/**
 * 부하 실험(#427·#446) 전용 가짜 발송처. FCM 대신 <b>지연·실패를 흉내 내고</b> 받은 것을 센다.
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
 * ({@link FcmMessageFactory})을 거친다. 공급자 왕복만 지연으로 바꾼다 — 기본 로그정규(평균 {@code loadtest.push.mean-ms},
 * 60ms), {@code loadtest.push.latency-max-ms} 가 있으면 [{@code latency-min-ms}, {@code latency-max-ms}] 고른 분포.
 *
 * <h2>장애 주입(#446 실험 3)</h2>
 * 실패는 FCM 발송처({@link FcmNotificationSender})와 <b>같은 모양</b>으로 던진다 — {@code "fcm: 코드"}, 영구 코드표도 같다.
 * 그래서 relay 의 재시도·백오프·만료·DEAD 계약이 실제와 같은 길을 탄다.
 * <ul>
 *   <li>{@code loadtest.push.errors} — {@code 코드:비율} 쉼표 목록(예 {@code UNAVAILABLE:0.1,QUOTA_EXCEEDED:0.05,UNREGISTERED:0.01}).
 *       호출마다 한 번 굴려 비율 합 안이면 그 코드로 실패. 지연을 다 쓴 뒤 실패한다(실제 FCM 이 5xx 를 답하는 모양)</li>
 *   <li>{@code loadtest.push.outage.start-epoch-s}·{@code outage.seconds}·{@code outage.code}(기본 UNAVAILABLE) — 그 창 동안
 *       모든 호출이 그 코드로 실패(전면 장애). {@code outage.latency-ms}(기본 0) 만큼 붙잡은 뒤 실패.
 *       {@code outage.control-file} 을 주면 그 파일의 한 줄(시작 epoch 초, 길이 초[, 코드])을 1초마다 읽는다 — 하네스가 폭주
 *       시작에 맞춰 쓴다</li>
 * </ul>
 * 끝에 {@code UNREGISTERED} 가 나와도 토큰을 끄지 않는다(심은 토큰을 지우면 다음 칸이 망가진다) — 영구 실패만 던진다.
 *
 * <h2>센다 — 불변식 확인용</h2>
 * <ul>
 *   <li>{@code loadtest.push.received{checkpoint,delivery}} — 받은(접수한) 수</li>
 *   <li>{@code loadtest.push.duplicate.id} — 같은 알림 행 번호를 두 번 받음(최소 한 번 보장의 재발송)</li>
 *   <li>{@code loadtest.push.duplicate.business} — 업무 키(여정·경로 버전·종류·환승·평가 번호·울림)가 같은 것을 두 번 받음</li>
 *   <li>{@code loadtest.push.alerting.per_revision_over1} — 위험·재탐색 울림을 한 경로 버전에 두 번 이상 받은 수. 0 이어야 한다</li>
 *   <li>{@code loadtest.push.fault{code}} — 주입한 실패 수</li>
 *   <li>{@code loadtest.push.log-path} — 접수마다 한 줄을 파일에 덧붙인다(epoch_ms, 행 번호, 업무 키, 종류, 울림, 여정|버전).
 *       위 카운터는 JVM 메모리라 worker 를 죽였다 살리면 0 부터 다시 센다 — 재기동을 넘는 중복은 이 파일로 센다</li>
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
    static final String FAULT = "loadtest.push.fault";

    /** 피로 정책이 울림을 경로 버전당 한 번으로 묶는 대상. 접근 알림은 안내라 빠진다. */
    private static final Set<JourneyCheckpoint> POLICY_CHECKPOINTS = Set.of(
            JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED,
            JourneyCheckpoint.REROUTE_SUGGESTED);

    /** {@link FcmNotificationSender} 의 영구 코드표와 같다. 그 밖은 일시 실패. */
    static final Set<String> PERMANENT_CODES = Set.of(
            "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH", "THIRD_PARTY_AUTH_ERROR");

    private final UserPushTokenRepository tokenRepository;
    private final FcmMessageFactory messageFactory;
    private final MeterRegistry registry;
    private final Environment environment;
    private final Clock clock;
    private final double meanMillis;
    private final Faults faults;
    private final Path logPath;
    /** 하네스가 장애 창을 적는 파일(한 줄: 시작 epoch 초, 길이 초, 코드). 1초마다만 다시 읽는다. */
    private final Path outageControl;
    private volatile long controlReadAt; // 0 에서 시작(MIN_VALUE 면 뺄셈이 넘쳐 영영 안 읽는다)
    private volatile String[] control;

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
        this.faults = Faults.from(environment, clock::millis, () -> ThreadLocalRandom.current().nextDouble());
        String path = environment.getProperty("loadtest.push.log-path", "");
        this.logPath = path.isBlank() ? null : Path.of(path);
        String ctl = environment.getProperty("loadtest.push.outage.control-file", "");
        this.outageControl = ctl.isBlank() ? null : Path.of(ctl);
    }

    @PostConstruct
    void refuseProduction() {
        if (Arrays.asList(environment.getActiveProfiles()).contains("prod")) {
            throw new IllegalStateException("가짜 발송처(loadtest)는 prod 프로필과 함께 뜰 수 없다 — "
                    + "알림을 받은 척 SENT 로 소모해 실제 발송을 영영 막는다");
        }
        Gauge.builder(ALERTING_OVER_ONE, revisionsOverOne, AtomicLong::doubleValue).register(registry);
        log.warn("[LoadTestSender] 가짜 발송처가 켜졌다 — 알림은 어디에도 나가지 않는다(부하 실험 전용). 장애 주입: {}", faults);
    }

    @Override
    public void send(NotificationSendCommand command) {
        if (tokenRepository.findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                command.userId()).isEmpty()) {
            throw new NotificationSendException("no-active-token", true);
        }
        PushMessage message = messageFactory.from(command, Instant.now(clock));
        long started = System.nanoTime();
        String outage = currentOutage();
        if (outage != null) {
            sleep(faults.outageLatencyMillis());
            fail(outage);
        }
        sleep(faults.hasLatencyRange() ? faults.uniformLatencyMillis() : lognormalMillis());
        Timer.builder(SEND).register(registry).record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        String injected = faults.rollError();
        if (injected != null) {
            fail(injected);
        }
        record(command, message.delivery());
    }

    /**
     * 지금 전면 장애 창이면 그 코드. 설정의 고정 창이 먼저, 없으면 제어 파일. 하네스는 부팅 전에 장애 시각을 모르므로
     * (폭주 시작에 맞춰야 한다) 그 순간에 파일을 쓴다.
     */
    String currentOutage() {
        String fixed = faults.outageCode();
        if (fixed != null || outageControl == null) {
            return fixed;
        }
        long now = clock.millis();
        if (now - controlReadAt >= 1000) {
            controlReadAt = now;
            try {
                control = Files.exists(outageControl)
                        ? Files.readString(outageControl).trim().split("\\s+") : null;
            } catch (IOException e) {
                control = null;
            }
        }
        String[] c = control;
        if (c == null || c.length < 2) {
            return null;
        }
        try {
            long start = Long.parseLong(c[0]) * 1000;
            long end = start + Long.parseLong(c[1]) * 1000;
            return now >= start && now < end ? (c.length > 2 ? c[2] : "UNAVAILABLE") : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** FCM 발송처와 같은 모양의 실패. 문구는 코드만, 영구 여부는 같은 표. */
    private void fail(String code) {
        registry.counter(FAULT, "code", code).increment();
        throw new NotificationSendException("fcm: " + code, PERMANENT_CODES.contains(code));
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
        String revision = command.journeyId() + "|" + command.routeRevision();
        if (delivery == PushMessage.Delivery.ALERTING && POLICY_CHECKPOINTS.contains(command.checkpoint())) {
            if (alertingByRevision.computeIfAbsent(revision, k -> new AtomicInteger()).incrementAndGet() == 2) {
                revisionsOverOne.incrementAndGet();
            }
        }
        appendLog(command.notificationId() + "\t" + business + "\t" + command.checkpoint() + "\t" + deliveryTag
                + "\t" + revision);
    }

    /** 접수 한 줄을 파일에 덧붙인다. 줄마다 열고 닫아 kill -9 에도 이미 쓴 줄은 남는다. */
    private void appendLog(String line) {
        if (logPath == null) {
            return;
        }
        byte[] bytes = (clock.millis() + "\t" + line + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (this) {
            try {
                Files.write(logPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("[LoadTestSender] 접수 기록 실패: {}", e.getMessage());
            }
        }
    }

    long revisionsWithMoreThanOneAlerting() {
        return revisionsOverOne.get();
    }

    /** 운영 FCM 왕복 흉내: 평균이 meanMillis 인 로그정규(σ=0.6). */
    private long lognormalMillis() {
        double sigma = 0.6;
        double mu = Math.log(Math.max(meanMillis, 1)) - sigma * sigma / 2;
        return (long) Math.exp(mu + sigma * ThreadLocalRandom.current().nextGaussian());
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotificationSendException("interrupted", false);
        }
    }

    /**
     * 장애 주입 설정(#446). 시험이 시계·난수를 바꿔 끼울 수 있게 떼어 둔다.
     *
     * @param errorRates  코드 → 비율(합 ≤ 1). 순서대로 누적해 굴린 값이 들어가는 코드로 실패
     * @param outageStartMs 전면 장애 시작(epoch ms). 0 이면 없음
     */
    record Faults(long latencyMinMs, long latencyMaxMs, Map<String, Double> errorRates,
                  long outageStartMs, long outageEndMs, String outageCodeName, long outageLatencyMs,
                  LongSupplier nowMillis, DoubleSupplier random) {

        static Faults from(Environment env, LongSupplier nowMillis, DoubleSupplier random) {
            long start = env.getProperty("loadtest.push.outage.start-epoch-s", Long.class, 0L) * 1000;
            long seconds = env.getProperty("loadtest.push.outage.seconds", Long.class, 0L);
            return new Faults(
                    env.getProperty("loadtest.push.latency-min-ms", Long.class, 0L),
                    env.getProperty("loadtest.push.latency-max-ms", Long.class, 0L),
                    parseRates(env.getProperty("loadtest.push.errors", "")),
                    start, start + seconds * 1000,
                    env.getProperty("loadtest.push.outage.code", "UNAVAILABLE"),
                    env.getProperty("loadtest.push.outage.latency-ms", Long.class, 0L),
                    nowMillis, random);
        }

        static Map<String, Double> parseRates(String spec) {
            Map<String, Double> rates = new LinkedHashMap<>();
            if (spec == null || spec.isBlank()) {
                return rates;
            }
            double sum = 0;
            for (String part : spec.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length != 2) {
                    throw new IllegalStateException("loadtest.push.errors 는 코드:비율 쉼표 목록이다: " + spec);
                }
                double rate = Double.parseDouble(kv[1].trim());
                sum += rate;
                rates.put(kv[0].trim(), rate);
            }
            if (sum > 1.0 + 1e-9) {
                throw new IllegalStateException("loadtest.push.errors 비율 합이 1 을 넘는다: " + spec);
            }
            return rates;
        }

        boolean hasLatencyRange() {
            return latencyMaxMs > 0;
        }

        long uniformLatencyMillis() {
            long span = Math.max(0, latencyMaxMs - latencyMinMs);
            return latencyMinMs + (long) (random.getAsDouble() * span);
        }

        /** @return 지금 전면 장애 창 안이면 그 코드, 아니면 null */
        String outageCode() {
            if (outageStartMs <= 0 || outageEndMs <= outageStartMs) {
                return null;
            }
            long now = nowMillis.getAsLong();
            return now >= outageStartMs && now < outageEndMs ? outageCodeName : null;
        }

        long outageLatencyMillis() {
            return outageLatencyMs;
        }

        /** @return 이번 호출에 주입할 실패 코드, 없으면 null */
        String rollError() {
            if (errorRates.isEmpty()) {
                return null;
            }
            double roll = random.getAsDouble();
            double acc = 0;
            for (Map.Entry<String, Double> e : errorRates.entrySet()) {
                acc += e.getValue();
                if (roll < acc) {
                    return e.getKey();
                }
            }
            return null;
        }

        @Override
        public String toString() {
            return "latency=" + (hasLatencyRange() ? latencyMinMs + "~" + latencyMaxMs + "ms" : "lognormal")
                    + " errors=" + errorRates
                    + (outageStartMs > 0 ? " outage=" + outageCodeName + "@" + outageStartMs / 1000 + "+"
                    + (outageEndMs - outageStartMs) / 1000 + "s" : "");
        }
    }
}
