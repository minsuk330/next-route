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

    /** 발송 구현 선택. PR3 이 {@code fcm} 을 추가한다. */
    private String sender = "noop";

    /** 채널 결정 구현 선택. PR3 이 {@code identity} 를 추가한다. */
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

    @Getter
    @Setter
    public static class Relay {

        /** 기본 꺼짐. 실제 발송 구현이 붙는 PR3 전까지는 쌓이기만 한다. */
        private boolean enabled = false;

        private long sweepIntervalMs = 1000;

        private int batchSize = 200;

        private int workers = 4;

        /** 한 번 깨어났을 때 이어서 도는 배치 수 상한. */
        private int maxBatches = 1000;

        /** 한 번 깨어났을 때 쓰는 시간 상한. */
        private long drainMaxMillis = 5000;
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
        if (relay.isEnabled() && "noop".equals(sender)) {
            throw new IllegalStateException(
                    "relay 를 켜면서 sender 를 noop 으로 두면 안 된다. 진짜 알림 행을 아무 데도 "
                            + "보내지 않은 채 SENT 로 소모해, 나중에 실제 발송을 영영 막는다");
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
