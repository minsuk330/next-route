package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설정이 서로 모순되면 <b>부팅에서</b> 막는다.
 *
 * <p>여기서 막는 것들은 운영에서 조용히 나타난다 — 알림이 안 나가거나, 나갔는데 아무 데도 안
 * 갔거나. 어느 쪽도 로그로는 드러나지 않는다.
 */
@DisplayName("여정 알림 설정 검증")
class JourneyNotificationPropertiesTest {

    private JourneyNotificationProperties properties() {
        return new JourneyNotificationProperties();
    }

    /** #451 이전 기본값(BATCH·FIXED·회수 3분). 옛 검사가 그대로 살아 있는지 보는 시험이 쓴다. */
    private JourneyNotificationProperties legacy() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        properties.getRelay().setMode(JourneyNotificationProperties.RelayMode.BATCH);
        properties.setRetryPolicy(JourneyNotificationProperties.RetryPolicy.FIXED);
        properties.setClaimTimeoutSeconds(null);
        return properties;
    }

    private void validate(JourneyNotificationProperties properties) {
        ReflectionTestUtils.invokeMethod(properties, "validate");
    }

    @Test
    @DisplayName("기본값 조합은 통과한다")
    void defaultsAreValid() {
        assertThatCode(() -> validate(properties())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("기본값은 PULL(워커 128)·EXPONENTIAL·회수 45초·SCHEDULED 다(#451 후속), 옛 기본값 조합도 통과한다")
    void defaultsAreMeasuredConfiguration() {
        JourneyNotificationProperties properties = properties();
        org.assertj.core.api.Assertions.assertThat(properties.getRelay().getMode())
                .isEqualTo(JourneyNotificationProperties.RelayMode.PULL);
        org.assertj.core.api.Assertions.assertThat(properties.getRetryPolicy())
                .isEqualTo(JourneyNotificationProperties.RetryPolicy.EXPONENTIAL);
        org.assertj.core.api.Assertions.assertThat(properties.claimTimeout()).isEqualTo(java.time.Duration.ofSeconds(45));
        org.assertj.core.api.Assertions.assertThat(properties.getRelay().getPullWorkers()).isEqualTo(128);
        org.assertj.core.api.Assertions.assertThat(properties.getRelay().getClaimOrder())
                .isEqualTo(JourneyNotificationProperties.ClaimOrder.SCHEDULED);
        assertThatCode(() -> validate(legacy())).as("env 로 BATCH·FIXED·180초 롤백").doesNotThrowAnyException();
    }

    @Test
    @DisplayName("유효시간 순서가 뒤집히면 막는다")
    void ttlOrderIsEnforced() {
        JourneyNotificationProperties properties = properties();
        properties.setMaxTtlSeconds(60);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("유효시간");
    }

    @Test
    @DisplayName("재시도 예산이 유효시간 바닥을 넘으면 막는다")
    void retryBudgetMustFitInsideMinTtl() {
        JourneyNotificationProperties properties = legacy();
        // 원안이던 60초 바닥이 여기 걸린다 — 60초 × 3회 = 180초 > 60초.
        properties.setMinTtlSeconds(60);

        assertThatThrownBy(() -> validate(properties))
                .as("걸리지 않으면 만료 바닥에 걸린 MISSED 가 재시도를 한 번도 못 하고 취소된다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("백오프");
    }

    // "relay 를 켠 채 sender=noop" 검증은 JourneyNotificationProperties 에서 빠졌다
    // (프로필 무관 config 빈이 api 까지 막던 문제). 그 계약은 JourneyRelaySenderGuardTest 가 진다.

    @Test
    @DisplayName("모르는 sender 이름은 막는다")
    void unknownSenderIsRejected() {
        JourneyNotificationProperties properties = properties();
        properties.setSender("fcmm");

        assertThatThrownBy(() -> validate(properties))
                .as("오타가 조용히 no-op 으로 떨어지면 알림이 안 나가는데 아무 데도 안 보인다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sender");
    }

    @Test
    @DisplayName("모르는 채널 결정자 이름은 막는다")
    void unknownChannelResolverIsRejected() {
        JourneyNotificationProperties properties = properties();
        properties.setChannelResolver("identityy");

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("channel-resolver");
    }

    @Test
    @DisplayName("sender=fcm 인데 자격증명 경로가 비면 막는다")
    void fcmWithoutCredentialsIsRejected() {
        JourneyNotificationProperties properties = properties();
        properties.setSender("fcm");

        assertThatThrownBy(() -> validate(properties))
                .as("켠다고 해 놓고 조용히 아무것도 안 보내는 상태가 가장 나쁘다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials-path");
    }

    @Test
    @DisplayName("자격증명이 있으면 fcm 기본값 조합도 통과한다")
    void fcmDefaultsPassWithCredentials() {
        JourneyNotificationProperties properties = properties();
        properties.setSender("fcm");
        properties.getFcm().setCredentialsPath("/secrets/firebase.json");

        assertThatCode(() -> validate(properties))
                .as("타임아웃 5,000ms 와 회수 주기 3분을 숫자로 견주면(5000*2 < 3) 정상 설정이 죽는다")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("발송 타임아웃 × 2 가 회수 주기를 넘으면 막는다")
    void sendTimeoutMustFitInsideClaimTimeout() {
        JourneyNotificationProperties properties = properties();
        properties.getFcm().setTimeoutMs(properties.claimTimeout().toMillis());

        assertThatThrownBy(() -> validate(properties))
                .as("길면 아직 발송 중인 행을 회수 루프가 다시 집어 가 같은 알림이 두 번 나간다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("회수 주기");
    }

    @Test
    @DisplayName("provider TTL 상한이 outbox 만료 상한보다 크면 막는다")
    void providerTtlCannotOutliveOutboxTtl() {
        JourneyNotificationProperties properties = properties();
        properties.getFcm().setTtlMaxSeconds(properties.getMaxTtlSeconds() + 1);

        assertThatThrownBy(() -> validate(properties))
                .as("provider 가 outbox 보다 오래 들고 있으면 만료된 알림이 나중에 도착한다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ttl-max-seconds");
    }

    // ── #451 relay 내구성 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("회수 기준은 초 키가 이기고, 없으면 옛 분 키를 쓴다")
    void claimTimeoutSecondsWinsOverMinutes() {
        JourneyNotificationProperties properties = legacy();
        org.assertj.core.api.Assertions.assertThat(properties.claimTimeout())
                .isEqualTo(java.time.Duration.ofMinutes(3));
        properties.setClaimTimeoutMinutes(5);
        org.assertj.core.api.Assertions.assertThat(properties.claimTimeout())
                .isEqualTo(java.time.Duration.ofMinutes(5));
        properties.setClaimTimeoutSeconds(45L);
        org.assertj.core.api.Assertions.assertThat(properties.claimTimeout())
                .isEqualTo(java.time.Duration.ofSeconds(45));
    }

    @Test
    @DisplayName("PULL 권장 조합(45초·heartbeat 10초)은 통과한다")
    void pullRecommendedIsValid() {
        JourneyNotificationProperties properties = properties();
        properties.getRelay().setMode(JourneyNotificationProperties.RelayMode.PULL);
        properties.setClaimTimeoutSeconds(45L);

        assertThatCode(() -> validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("heartbeat 주기 × 4 가 회수 기준을 넘으면 막는다")
    void heartbeatMustFitInsideClaimTimeout() {
        JourneyNotificationProperties properties = properties();
        properties.setClaimTimeoutSeconds(45L);
        properties.getRelay().setHeartbeatIntervalMs(15_000);

        assertThatThrownBy(() -> validate(properties))
                .as("× 3 정확히면 회수 문(직전 heartbeat 가 기준/3 안)이 스케줄 지연에 영영 닫힌다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("heartbeat");
    }

    @Test
    @DisplayName("PULL 에서 회수 기준 + 백오프가 유효시간 바닥 이상이면 막는다(BATCH 기본값은 그대로 통과)")
    void pullClaimTimeoutPlusBackoffMustFitInsideMinTtl() {
        JourneyNotificationProperties properties = legacy();
        properties.getRelay().setMode(JourneyNotificationProperties.RelayMode.PULL);

        assertThatThrownBy(() -> validate(properties))
                .as("기본 180초 + 60초 > 180초 — 워커가 죽은 행이 회수 전에 만료된다")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PULL");
    }

    @Test
    @DisplayName("FCM HTTP 최대 수명 × 2 가 회수 기준 이상이면 막는다")
    void httpLifetimeMustFitInsideClaimTimeout() {
        JourneyNotificationProperties properties = properties();
        properties.setClaimTimeoutSeconds(45L);
        // 2 + 5 × 5 = 27초, × 2 = 54초 ≥ 45초
        properties.getFcm().setReadTimeoutMs(5_000);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 최대 수명");
    }

    @Test
    @DisplayName("FCM HTTP 최대 수명이 백오프 이상이면 막는다")
    void httpLifetimeMustBeShorterThanBackoff() {
        JourneyNotificationProperties properties = legacy();
        // 2 + 12 × 5 = 62초 ≥ 백오프 60초(× 2 = 124 < 180 은 통과)
        properties.getFcm().setReadTimeoutMs(12_000);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("백오프");
    }

    @Test
    @DisplayName("FCM 시한 0(무한)·PULL 워커 0 은 막는다")
    void zeroTimeoutsAndWorkersAreRejected() {
        JourneyNotificationProperties zeroRead = properties();
        zeroRead.getFcm().setReadTimeoutMs(0);
        assertThatThrownBy(() -> validate(zeroRead)).isInstanceOf(IllegalStateException.class);

        JourneyNotificationProperties zeroWorkers = properties();
        zeroWorkers.getRelay().setPullWorkers(0);
        assertThatThrownBy(() -> validate(zeroWorkers)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("claim 순서 기본값은 SCHEDULED(기존 순서), aging 60초")
    void claimOrderDefaultsToScheduled() {
        JourneyNotificationProperties.Relay relay = properties().getRelay();

        assertThat(relay.getClaimOrder()).isEqualTo(JourneyNotificationProperties.ClaimOrder.SCHEDULED);
        assertThat(relay.getAgingSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("claim 순서가 없거나 aging 이 0 이하면 막는다")
    void claimOrderAndAgingAreValidated() {
        JourneyNotificationProperties nullOrder = properties();
        nullOrder.getRelay().setClaimOrder(null);
        assertThatThrownBy(() -> validate(nullOrder)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("claim-order");

        JourneyNotificationProperties zeroAging = properties();
        zeroAging.getRelay().setAgingSeconds(0);
        assertThatThrownBy(() -> validate(zeroAging)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aging-seconds");

        JourneyNotificationProperties priorityFirst = properties();
        priorityFirst.getRelay().setClaimOrder(JourneyNotificationProperties.ClaimOrder.PRIORITY_FIRST);
        assertThatCode(() -> validate(priorityFirst)).doesNotThrowAnyException();
    }

    // ---- #451 PR-3 재시도 정책 부팅 검증 ----

    private JourneyNotificationProperties exponential() {
        JourneyNotificationProperties properties = properties();
        properties.setRetryPolicy(JourneyNotificationProperties.RetryPolicy.EXPONENTIAL);
        properties.setClaimTimeoutSeconds(45L);
        return properties;
    }

    @Test
    @DisplayName("EXPONENTIAL + 회수 기준 45초 기본 조합은 통과한다(BATCH·PULL 둘 다)")
    void exponentialWithShortClaimTimeoutIsValid() {
        assertThatCode(() -> validate(exponential())).doesNotThrowAnyException();
        JourneyNotificationProperties pull = exponential();
        pull.getRelay().setMode(JourneyNotificationProperties.RelayMode.PULL);
        assertThatCode(() -> validate(pull)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EXPONENTIAL 은 회수 기준 + 첫 간격(지터 위쪽 24 × 1.2 = 28.8초)이 유효시간 바닥 이상이면 막는다")
    void exponentialFirstRetryPlusClaimTimeoutMustFitMinTtl() {
        JourneyNotificationProperties properties = exponential();
        // 회수 기준 기본(180초) 그대로면 180 + 28.8 ≥ 180
        properties.setClaimTimeoutSeconds(null);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("첫 재시도 간격");

        JourneyNotificationProperties edge = exponential();
        // 152 + 28.8 = 180.8 ≥ 180 → 막힘, 151 + 28.8 = 179.8 → 통과
        edge.setClaimTimeoutSeconds(152L);
        assertThatThrownBy(() -> validate(edge)).isInstanceOf(IllegalStateException.class);
        edge.setClaimTimeoutSeconds(151L);
        assertThatCode(() -> validate(edge)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EXPONENTIAL 첫 간격이 FCM HTTP 최대 수명(22초) 이하이면 막는다")
    void exponentialFirstRetryMustOutliveHttpLifetime() {
        JourneyNotificationProperties properties = exponential();
        properties.getRetry().setInitialMs(22_000L);

        assertThatThrownBy(() -> validate(properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTP 최대 수명");

        properties.getRetry().setInitialMs(22_001L);
        assertThatCode(() -> validate(properties)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("FIXED 의 백오프 × 시도 검사는 EXPONENTIAL 에서는 보지 않는다")
    void fixedBudgetCheckOnlyForFixed() {
        // 바닥 90초: FIXED 는 60 × 3 = 180 > 90 으로 막히고, EXPONENTIAL 은 45 + 28.8 < 90 이라 통과
        JourneyNotificationProperties fixed = legacy();
        fixed.setMinTtlSeconds(90);
        assertThatThrownBy(() -> validate(fixed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("백오프");

        JourneyNotificationProperties exp = exponential();
        exp.setMinTtlSeconds(90);
        assertThatCode(() -> validate(exp)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("EXPONENTIAL 값이 엉터리면 막는다(지터 ≥ 1, 배수 < 1, 상한 0, 첫 간격 > 최대)")
    void exponentialParametersAreValidated() {
        JourneyNotificationProperties jitter = exponential();
        jitter.getRetry().setJitter(1.0);
        assertThatThrownBy(() -> validate(jitter)).isInstanceOf(IllegalStateException.class);

        JourneyNotificationProperties multiplier = exponential();
        multiplier.getRetry().setMultiplier(0.5);
        assertThatThrownBy(() -> validate(multiplier)).isInstanceOf(IllegalStateException.class);

        JourneyNotificationProperties cap = exponential();
        cap.getRetry().setMaxAttemptsCap(0);
        assertThatThrownBy(() -> validate(cap)).isInstanceOf(IllegalStateException.class);

        JourneyNotificationProperties initial = exponential();
        initial.getRetry().setInitialMs(61_000L);
        assertThatThrownBy(() -> validate(initial)).isInstanceOf(IllegalStateException.class);

        JourneyNotificationProperties none = properties();
        none.setRetryPolicy(null);
        assertThatThrownBy(() -> validate(none)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("FIXED 는 HTTP 수명 < 백오프 검사를 그대로 본다(EXPONENTIAL 은 첫 간격으로 대신)")
    void fixedKeepsHttpLifetimeVsBackoff() {
        JourneyNotificationProperties exp = exponential();
        // FIXED 였다면 HTTP 수명 22초 ≥ 백오프 20초로 막힘. EXPONENTIAL 은 backoff-seconds 를 쓰지 않는다
        exp.setBackoffSeconds(20);
        assertThatCode(() -> validate(exp)).doesNotThrowAnyException();

        JourneyNotificationProperties fixed = legacy();
        fixed.setBackoffSeconds(20);
        assertThatThrownBy(() -> validate(fixed)).isInstanceOf(IllegalStateException.class);
    }
}
