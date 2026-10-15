package watoo.grd.nextroute.application.journey.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 여정 알림 outbox 의 운영값.
 *
 * <p>스위치가 넷이다.
 * <ul>
 *   <li>{@code journey.navigation.evaluator.enabled}(기존) — 꺼짐. 평가기 빈 자체가 안 뜬다</li>
 *   <li>{@link #enqueueEnabled} — 켜짐. 평가기는 도는데 알림만 끄고 싶을 때의 kill switch</li>
 *   <li>{@link Relay#enabled} — 켜짐. 단 relay 루프({@code JourneyNotificationRelayLoop})는
 *       {@code @Profile("worker")} 라 worker 에서만 돈다. api 는 이 값이 켜져 있어도 루프가 없다</li>
 *   <li>{@link #sender} — {@code noop} 이 기본, worker 는 {@code env/fcm.env} 로 {@code fcm}.
 *       relay 가 실제로 도는 프로세스에서 noop 이면 부팅이 막힌다({@code JourneyRelaySenderGuard})</li>
 * </ul>
 *
 * <p>취소 갈고리에는 스위치를 걸지 않는다. 평가기를 껐다 켜는 사이 PENDING 이 남으면 죽은
 * 여정의 알림이 나중에 나간다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.notification")
public class JourneyNotificationProperties {

    /**
     * {@code loadtest} 는 부하 실험(#427) 전용 가짜 발송처. {@code loadtest} 프로필에서만 빈이 뜨므로 프로필 없이
     * 이 값을 주면 발송처 빈이 없어 부팅이 죽는다({@code LoadTestNotificationSender}).
     */
    private static final Set<String> KNOWN_SENDERS = Set.of("noop", "fcm", "loadtest");
    private static final Set<String> KNOWN_CHANNEL_RESOLVERS = Set.of("fixed", "identity");

    /** 평가기는 돌되 알림만 끄는 스위치. */
    private boolean enqueueEnabled = true;

    /** 발송 구현 선택. {@code fcm} 은 worker 에서만 올린다(자격증명이 거기에만 있다). */
    private String sender = "noop";

    /**
     * 채널 결정 구현 선택.
     *
     * <p>{@code identity} 구현은 아직 없다. 토스 메신저 채널이 실제로 생기는 날까지 이름만 열어
     * 두고, 여정 알림은 {@code fixed}({@code [PUSH]}) 하나로 간다
     * ({@code TECHNICAL_DECISIONS §2} LOCKED).
     */
    private String channelResolver = "fixed";

    /** {@code fixed} 결정자가 늘 돌려줄 채널 목록. */
    private List<String> fixedChannels = List.of("PUSH");

    /**
     * 유효시간의 바닥.
     *
     * <p>MISSED 는 여유가 음수라는 뜻 = 다음 차 도착 시각이 <b>이미 과거</b>다. 그 값을 그대로
     * 만료로 쓰면 삽입 순간 이미 만료라 relay 가 첫 claim 에서 취소한다 — 가장 중요한 알림이
     * 사라진다.
     *
     * <p>180초인 이유는 재시도가 만료 안에서 끝나야 하기 때문이다. 백오프 60초 × 최대 3회 =
     * 180초이므로 이보다 작으면 만료 바닥에 걸린 행이 재시도를 <b>한 번도</b> 못 한다.
     */
    private long minTtlSeconds = 180;

    /** 다음 차 도착 시각을 모를 때 쓰는 유효시간. */
    private long defaultTtlSeconds = 600;

    /** 유효시간의 천장. 30분보다 뒤에 도착하는 알림은 사용자를 엉뚱한 때에 움직인다. */
    private long maxTtlSeconds = 1800;

    /**
     * 한 행이 쓸 수 있는 시도 횟수. 넘기면 DEAD.
     *
     * <p>시도 횟수는 claim SQL 이 올린다 — 발송 직전 재확인이 취소한 행도 시도를 하나 쓴다는
     * 뜻이다. 워커가 세 번 죽으면 그 행은 보내지 못한 채 DEAD 가 된다(버스와 동일, 수용).
     */
    private int maxAttempts = 3;

    /** 일시 실패 뒤 다시 시도하기까지의 간격(고정). */
    private long backoffSeconds = 60;

    /**
     * 옛 회수 기준(분). {@link #claimTimeoutSeconds} 가 비었을 때만 쓴다 — 키 호환용이다(#451).
     */
    private long claimTimeoutMinutes = 3;

    /**
     * 이 시간 동안 heartbeat 가 없는 PROCESSING 행은 워커가 죽은 것으로 보고 회수한다(#451). 값이 있으면
     * {@link #claimTimeoutMinutes} 보다 이긴다. 비면 분 키(기본 3분 = 180초)를 쓴다.
     */
    private Long claimTimeoutSeconds;

    private final Relay relay = new Relay();

    private final Fcm fcm = new Fcm();

    private final Approach approach = new Approach();

    private final Fatigue fatigue = new Fatigue();

    private final E2e e2e = new E2e();

    /**
     * 처음부터 끝까지 지표(#446). 변화 피드가 처음 본 시각을 여정마다 Redis 에 남기고, 평가가 그것을 꺼내 위험 알림
     * payload 에 싣고, 발송 접수 때 {@code journey.notification.e2e} 로 잰다.
     *
     * <p>측정 전용이다. 끄면 표시를 남기지도 꺼내지도 않는다 — payload 의 처음 본 시각은 평가 시작 시각(하한)으로만 남는다.
     */
    @Getter
    @Setter
    public static class E2e {

        private boolean enabled = true;

        /** 아무 평가도 소비하지 않은 표시의 수명. 여정이 끝나 평가가 없으면 이 시간 뒤 사라진다. */
        private long stampTtlSeconds = 600;
    }

    /**
     * 악화 알림 피로 정책(#428). 경로 버전마다 울림 한 번, 그 뒤는 조용한 갱신.
     *
     * <p>꺼져 있으면 발송 메시지가 정책 이전과 똑같다 — 갱신 열쇠·tag·채널·data 어느 것도 바뀌지 않는다.
     * 판정(PR-1b)도 마찬가지로 꺼져 있으면 울릴 권리 칸을 읽지도 쓰지도 않는다.
     */
    @Getter
    @Setter
    public static class Fatigue {

        private boolean enabled = false;

        /** 앱이 만든 저중요도 Android 채널 이름. 앱과 글자까지 같아야 한다. */
        private String quietChannelId = "journey_quiet";

        /**
         * 합치기 창의 수집 한 번(초). 첫 울림을 다음 수집 결과를 받아 본 뒤에 보낸다 — 그 사이 더 나쁜 소식이나
         * 대안이 오면 대체해 한 번에 보낸다. 지하철 수집 주기와 같다.
         */
        private long holdRoundSeconds = 15;

        /** 다음 수집의 평가가 끝날 여유(초). 수집 직후 바로 발송하면 그 평가가 대체할 틈이 없다. */
        private long holdMarginSeconds = 2;

        /**
         * 행동 시간 여유(초). 빠듯 알림의 보류는 "다음 차 도착 − 지금 − 이 값"을 넘지 않는다 — 기다리다 사용자가
         * 움직일 시간을 잃으면 합친 알림이 쓸모없다. 0 이하가 되면 즉시 보낸다.
         */
        private long holdActionMarginSeconds = 30;
    }

    /**
     * 접근 알림(N2) 설정. "갈아탈 차가 곧 온다"를 언제 알릴지 정한다.
     *
     * <p>위험 알림과 스위치가 갈려 있다 — N2 가 시끄러우면 그것만 끄고 위험 알림은 남긴다.
     */
    @Getter
    @Setter
    public static class Approach {

        /** N2 만 끄는 kill switch. 위험 알림은 그대로 간다. */
        private boolean enabled = true;

        /**
         * 이 시간 안에 도착하면 "곧 온다"로 본다.
         *
         * <p>이 값이 N2 의 <b>유효시간 상한</b>이기도 하다. 접근 알림은 바닥·천장 clamp 없이
         * 다음 차 도착 시각을 그대로 만료로 쓰는데, 조건이 "도착이 창 안"이라 만료가 창을 넘지
         * 못하기 때문이다.
         */
        private long windowSeconds = 120;

        public Duration window() {
            return Duration.ofSeconds(windowSeconds);
        }
    }

    /**
     * relay 가 행을 집는 방식(#451).
     * <ul>
     *   <li>{@code BATCH} — 기존. 배치 하나를 잡아 고정 풀로 보내고 전부 끝날 때까지 기다린다</li>
     *   <li>{@code PULL} — 워커 N개가 각자 k건씩 당겨 간다. 한 워커가 막혀도 나머지가 계속 집는다</li>
     * </ul>
     */
    public enum RelayMode { BATCH, PULL }

    @Getter
    @Setter
    public static class Relay {

        /** 기본 BATCH(기존 동작). env {@code JOURNEY_NOTIFICATION_RELAY_MODE}. */
        private RelayMode mode = RelayMode.BATCH;

        /** PULL 워커 수. 각 워커가 발송 한 건 동안 커넥션 없이 기다리고, claim·기록 때만 커넥션을 쓴다. */
        private int pullWorkers = 32;

        /** PULL 워커가 한 번에 집는 건수. 작을수록 느린 발송 하나가 붙잡는 행이 적다. */
        private int pullClaimSize = 2;

        /**
         * 진행 중 행의 {@code processing_started_at} 을 DB {@code now()} 로 갱신하는 주기. 회수는 같은 스레드가
         * heartbeat 바로 뒤에 한다. BATCH·PULL 공통.
         */
        private long heartbeatIntervalMs = 10_000;

        /**
         * 기본 켜짐. ⑤(발송 직전 재검증)·⑥(payload 계약)이 들어와 발송이 라이브라 프로덕션 의도는
         * relay ON 이다. 실제 발송은 worker 에서만 일어난다 — 루프({@code JourneyNotificationRelayLoop})가
         * {@code @Profile("worker")} + 이 플래그일 때만 뜨기 때문이다. api 는 켜져 있어도 루프가 없어
         * 행을 집지 않는다.
         */
        private boolean enabled = true;

        private long sweepIntervalMs = 1000;

        private int batchSize = 200;

        private int workers = 4;

        /** 한 번 깨어났을 때 이어서 도는 배치 수 상한. */
        private int maxBatches = 1000;

        /** 한 번 깨어났을 때 쓰는 시간 상한. */
        private long drainMaxMillis = 5000;
    }

    /**
     * FCM 발송 설정. {@code sender=fcm} 일 때만 뜻이 있다.
     *
     * <p>서비스 계정 JSON 은 저장소에 넣지 않는다. 컨테이너에 읽기 전용 볼륨으로 넣고 경로만
     * 준다 — base64 로 환경변수에 통째로 넣으면 프로세스 목록과 크래시 덤프에 남는다.
     */
    @Getter
    @Setter
    public static class Fcm {

        /** 서비스 계정 JSON 의 경로. {@code sender=fcm} 인데 비어 있으면 부팅을 막는다. */
        private String credentialsPath = "";

        /**
         * 행 하나 전체의 발송 예산. chunk 마다가 아니다.
         *
         * <p>token 이 500개를 넘으면 chunk 가 여러 번 도는데, chunk 별 예산이면 합이 회수 주기를
         * 넘어 아직 발송 중인 행을 회수 루프가 다시 집어 간다.
         */
        private long timeoutMs = 5000;

        /** provider 가 메시지를 들고 있을 시간의 천장. outbox 의 {@code max-ttl-seconds} 이하여야 한다. */
        private long ttlMaxSeconds = 1800;

        public Duration timeout() {
            return Duration.ofMillis(timeoutMs);
        }

        public Duration ttlMax() {
            return Duration.ofSeconds(ttlMaxSeconds);
        }

        /** SDK HTTP 연결 시한({@code FirebaseOptions.setConnectTimeout}). 0 이면 무한이라 막는다. */
        private int connectTimeoutMs = 2_000;

        /** SDK HTTP 읽기 시한({@code FirebaseOptions.setReadTimeout}). 0 이면 무한이라 막는다. */
        private int readTimeoutMs = 4_000;

        /**
         * firebase-admin 9.10.0 의 메시징 재시도 횟수. {@code ApiClientUtils.DEFAULT_RETRY_CONFIG}
         * (maxRetries=4, 503 만, IOException 재시도 없음)이고 공개 API 로 바꿀 수 없다 — 그래서 상수로 두고 수명 계산에 넣는다.
         */
        public static final int SDK_MAX_RETRIES = 4;

        /**
         * HTTP 요청 하나가 SDK 안에서 살 수 있는 최대 시간 = 연결 + 읽기 × (1 + SDK 재시도). 재시도 사이 대기는 빼고 센다 —
         * 시한 초과 때 future 를 {@code cancel(true)} 하면 그 대기가 끊겨 재시도가 멈춘다({@code RetryUnsuccessfulResponseHandler}
         * 가 InterruptedException 에서 false). 계산은 그 취소가 없을 때도 버티게 보수적으로 둔다.
         */
        public Duration httpMaxLifetime() {
            return Duration.ofMillis((long) connectTimeoutMs + (long) readTimeoutMs * (1 + SDK_MAX_RETRIES));
        }
    }

    @PostConstruct
    void validate() {
        if (minTtlSeconds <= 0 || defaultTtlSeconds <= 0 || maxTtlSeconds <= 0
                || maxAttempts <= 0 || backoffSeconds <= 0 || claimTimeoutMinutes <= 0
                || (claimTimeoutSeconds != null && claimTimeoutSeconds <= 0)) {
            throw new IllegalStateException("journey.notification 설정은 모두 0 보다 커야 한다");
        }
        if (minTtlSeconds > defaultTtlSeconds || defaultTtlSeconds > maxTtlSeconds) {
            throw new IllegalStateException(
                    "유효시간은 min ≤ 기본 ≤ max 순서여야 한다. 뒤집히면 clamp 가 기본값을 쓰지 않는다");
        }
        if (backoffSeconds * maxAttempts > minTtlSeconds) {
            throw new IllegalStateException(
                    "백오프 × 최대 시도가 유효시간 바닥을 넘으면 안 된다. 넘으면 만료 바닥에 걸린 "
                            + "행(MISSED)이 재시도를 한 번도 못 하고 취소된다");
        }
        if (!KNOWN_SENDERS.contains(sender)) {
            throw new IllegalStateException(
                    "모르는 sender: " + sender + ". 오타가 조용히 no-op 으로 떨어지면 안 된다");
        }
        if (!KNOWN_CHANNEL_RESOLVERS.contains(channelResolver)) {
            throw new IllegalStateException("모르는 channel-resolver: " + channelResolver);
        }
        // "relay 를 켠 채 sender=noop" 검증은 여기 두지 않는다. 이 config 빈은 프로필과 무관하게
        // 모든 프로세스에서 뜨는데, relay 루프는 worker 에서만 돈다. relay 를 절대 돌리지 않는 api
        // (발송 자격이 없어 sender=noop)까지 막지 않도록, 그 검증은 루프와 같은 조건의
        // JourneyRelaySenderGuard(@Profile("worker") + relay.enabled=true)로 옮겼다.
        validateApproach();
        validateRelay();
        validateFcm();
        validateFatigue();
        if (e2e.getStampTtlSeconds() <= 0) {
            throw new IllegalStateException("journey.notification.e2e.stamp-ttl-seconds 는 0 보다 커야 한다");
        }
    }

    /**
     * heartbeat·회수·PULL 설정(#451).
     *
     * <p>heartbeat 주기 × 4 ≤ 회수 기준이다(플랜은 × 3). 회수는 "이 인스턴스의 직전 heartbeat 성공이 회수 기준/3 안"일 때만
     * 열리는데, 주기가 정확히 그 값이면 스케줄 지연 몇 ms 로 문이 영영 닫혀 고착 행이 회수되지 않는다. 그래서 한 칸 여유를 둔다.
     */
    private void validateRelay() {
        if (relay.getHeartbeatIntervalMs() <= 0 || relay.getPullWorkers() <= 0 || relay.getPullClaimSize() <= 0) {
            throw new IllegalStateException(
                    "journey.notification.relay heartbeat-interval-ms·pull-workers·pull-claim-size 는 0 보다 커야 한다");
        }
        if (relay.getMode() == null) {
            throw new IllegalStateException("journey.notification.relay.mode 는 BATCH 또는 PULL 이어야 한다");
        }
        if (Duration.ofMillis(relay.getHeartbeatIntervalMs()).multipliedBy(4).compareTo(claimTimeout()) > 0) {
            throw new IllegalStateException(
                    "heartbeat 주기 × 4 는 회수 기준 이하여야 한다. 길면 살아 있는 워커의 행을 회수하거나 "
                            + "회수 문(직전 heartbeat 가 회수 기준/3 안)이 열리지 않는다");
        }
        // 회수 기준 + 첫 백오프 < 유효시간 바닥: 회수된 행이 재시도까지 마칠 시간이 남아야 한다. 기존 BATCH 기본값
        // (180초 + 60초 > 180초)은 이것을 못 지키므로 PULL 에서만 막는다 — 기본값을 그대로 둔 채 부팅이 죽으면 안 된다.
        if (relay.getMode() == RelayMode.PULL && claimTimeout().plus(backoff()).compareTo(minTtl()) >= 0) {
            throw new IllegalStateException(
                    "PULL 모드에서는 회수 기준 + 백오프가 유효시간 바닥보다 짧아야 한다(권장 claim-timeout-seconds=45). "
                            + "길면 워커가 죽은 행이 회수되기 전에 만료된다");
        }
    }

    private void validateFatigue() {
        if (fatigue.getHoldRoundSeconds() <= 0 || fatigue.getHoldMarginSeconds() < 0
                || fatigue.getHoldActionMarginSeconds() < 0) {
            throw new IllegalStateException(
                    "journey.notification.fatigue 보류 설정은 수집 주기 > 0, 여유 ≥ 0 이어야 한다");
        }
    }

    private void validateApproach() {
        if (approach.getWindowSeconds() <= 0) {
            throw new IllegalStateException(
                    "journey.notification.approach.window-seconds 는 0 보다 커야 한다");
        }
        if (approach.getWindowSeconds() > maxTtlSeconds) {
            throw new IllegalStateException(
                    "approach.window-seconds 는 max-ttl-seconds 이하여야 한다. 넘으면 접근 알림만 "
                            + "천장보다 오래 살아 알림 수명의 상한이 두 벌이 된다");
        }
    }

    private void validateFcm() {
        if (fcm.getTimeoutMs() <= 0 || fcm.getTtlMaxSeconds() <= 0) {
            throw new IllegalStateException("journey.notification.fcm 설정은 모두 0 보다 커야 한다");
        }
        // 단위를 맞춘 비교다. 5000(밀리초)과 3(분)을 숫자로 견주면 정상 설정이 부팅에서 죽는다.
        if (fcm.timeout().multipliedBy(2).compareTo(claimTimeout()) >= 0) {
            throw new IllegalStateException(
                    "발송 타임아웃 × 2 는 회수 주기보다 짧아야 한다. 길면 아직 발송 중인 행을 "
                            + "회수 루프가 다시 집어 가 같은 알림이 두 번 나간다");
        }
        if (fcm.getConnectTimeoutMs() <= 0 || fcm.getReadTimeoutMs() <= 0) {
            throw new IllegalStateException(
                    "journey.notification.fcm connect·read-timeout-ms 는 0 보다 커야 한다. 0 은 SDK 에서 무한이다");
        }
        if (fcm.httpMaxLifetime().multipliedBy(2).compareTo(claimTimeout()) >= 0) {
            throw new IllegalStateException(
                    "FCM HTTP 최대 수명(연결 + 읽기 × (1 + SDK 재시도 " + Fcm.SDK_MAX_RETRIES + ")) × 2 는 회수 기준보다 "
                            + "짧아야 한다. 길면 아직 공급자와 통화 중인 행을 회수해 같은 알림이 두 번 나간다");
        }
        if (fcm.httpMaxLifetime().compareTo(backoff()) >= 0) {
            throw new IllegalStateException(
                    "FCM HTTP 최대 수명은 백오프보다 짧아야 한다. 길면 앞 시도가 아직 살아 있는데 재시도가 나간다");
        }
        if (fcm.getTtlMaxSeconds() > maxTtlSeconds) {
            throw new IllegalStateException(
                    "fcm.ttl-max-seconds 는 max-ttl-seconds 이하여야 한다. provider 가 outbox 보다 "
                            + "오래 들고 있으면 만료된 알림이 나중에 도착한다");
        }
        if ("fcm".equals(sender) && (fcm.getCredentialsPath() == null
                || fcm.getCredentialsPath().isBlank())) {
            throw new IllegalStateException(
                    "sender=fcm 인데 fcm.credentials-path 가 비었다. 조용히 아무것도 안 보내는 "
                            + "상태가 가장 나쁘다");
        }
    }

    public Duration minTtl() {
        return Duration.ofSeconds(minTtlSeconds);
    }

    public Duration defaultTtl() {
        return Duration.ofSeconds(defaultTtlSeconds);
    }

    public Duration maxTtl() {
        return Duration.ofSeconds(maxTtlSeconds);
    }

    public Duration backoff() {
        return Duration.ofSeconds(backoffSeconds);
    }

    /** 회수 기준. 초 키가 있으면 그것, 없으면 옛 분 키(#451 호환). */
    public Duration claimTimeout() {
        return claimTimeoutSeconds != null
                ? Duration.ofSeconds(claimTimeoutSeconds)
                : Duration.ofMinutes(claimTimeoutMinutes);
    }

    public Duration heartbeatInterval() {
        return Duration.ofMillis(relay.getHeartbeatIntervalMs());
    }
}
