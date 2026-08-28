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
 * <p>스위치가 넷이고 기본은 전부 "아무 일도 안 일어나는" 조합이다.
 * <ul>
 *   <li>{@code journey.navigation.evaluator.enabled}(기존) — 꺼짐. 평가기 빈 자체가 안 뜬다</li>
 *   <li>{@link #enqueueEnabled} — 켜짐. 평가기는 도는데 알림만 끄고 싶을 때의 kill switch</li>
 *   <li>{@link Relay#enabled} — 꺼짐. relay 루프 빈이 안 뜬다(쌓이기만 한다)</li>
 *   <li>{@link #sender} — {@code noop}. 아무것도 안 보낸다</li>
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

    private static final Set<String> KNOWN_SENDERS = Set.of("noop", "fcm");
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

    /** 이 시간을 넘겨 PROCESSING 인 행은 워커가 죽은 것으로 보고 회수한다. */
    private long claimTimeoutMinutes = 3;

    private final Relay relay = new Relay();

    private final Fcm fcm = new Fcm();

    private final Approach approach = new Approach();

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

    @Getter
    @Setter
    public static class Relay {

        /**
         * 기본 꺼짐. 발송 구현이 붙어도 ⑤(발송 직전 재검증)·⑥(payload 계약)이 들어오기 전에는
         * 켜지 않는다 — 낡은 알림이 나가고 임시 문구가 운영에 굳는다.
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
    }

    @PostConstruct
    void validate() {
        if (minTtlSeconds <= 0 || defaultTtlSeconds <= 0 || maxTtlSeconds <= 0
                || maxAttempts <= 0 || backoffSeconds <= 0 || claimTimeoutMinutes <= 0) {
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
        validateFcm();
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

    public Duration claimTimeout() {
        return Duration.ofMinutes(claimTimeoutMinutes);
    }
}
