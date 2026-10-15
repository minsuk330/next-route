package watoo.grd.nextroute.application.journey.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 잘못 두면 조용히 나빠지는 설정 조합을 부팅에서 막는지 본다.
 *
 * <p>가장 중요한 것은 최대 간격과 낡음 기준의 관계다. 어겨도 예외가 나지 않고 앱에 "모름"만 늘어나서
 * 운영에서 원인을 찾기 어렵다.
 */
@DisplayName("평가기 설정 검증")
class JourneyEvaluatorPropertiesTest {

    @Test
    void TC_기본값은_통과한다() {
        assertThatCode(() -> new JourneyEvaluatorProperties().validate()).doesNotThrowAnyException();
    }

    @Test
    void TC_최대_간격이_낡음_기준보다_길면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setStaleAfterSeconds(120);
        props.setMaxIntervalSeconds(180);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("낡았다고 버려");
    }

    @Test
    void TC_최대_간격에_밀린_여정_상한을_더해_낡음_기준에_닿으면_부팅을_막는다() {
        // 급하지 않은 여정은 최대 간격만큼 미뤄진 뒤 줄에서 상한만큼 더 기다릴 수 있다. 그 합이 낡음
        // 기준에 닿으면 기다리는 동안 판정이 낡아 앱이 모름을 받는다.
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setStarvationGuardSeconds(120);   // 180 + 120 = 300 = stale-after

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("밀린 여정 상한");
    }

    @Test
    void TC_밀린_여정_상한이_0_이하면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setStarvationGuardSeconds(0);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_기본_간격이_최소와_최대_밖이면_부팅을_막는다() {
        JourneyEvaluatorProperties tooSmall = new JourneyEvaluatorProperties();
        tooSmall.setMinIntervalSeconds(60);   // 기본 30초가 최소보다 작다

        assertThatThrownBy(tooSmall::validate).isInstanceOf(IllegalStateException.class);

        JourneyEvaluatorProperties tooLarge = new JourneyEvaluatorProperties();
        tooLarge.setMaxIntervalSeconds(20);   // 기본 30초가 최대보다 크다

        assertThatThrownBy(tooLarge::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_임박_기준이_한가함_기준보다_길면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setNearTransferSeconds(900);
        props.setFarTransferSeconds(300);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_예측_예산_두_배가_배치_상한을_넘으면_부팅을_막는다() {
        // 환승 두 개짜리 여정이 배치 안에 못 끝나면 매 tick 미완료가 나는데, 로그만 봐서는
        // 그 이유가 설정 때문인지 provider 때문인지 알 수 없다.
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setPredictDeadlineMs(2_500);   // 배치 상한 4000ms 의 절반을 넘는다

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("배치 상한");
    }

    @Test
    void TC_유예가_낡음_기준보다_길면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setUnknownGraceSeconds(301);   // stale-after 300초보다 길다

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("붙잡");
    }

    @Test
    void TC_워커_큐가_선점_배치보다_작으면_부팅을_막는다() {
        // 작으면 선점까지 해 놓고 큐에서 거부돼, 그 여정은 선점 만료(60초)까지 방치된다.
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setBatchSize(500);
        props.setQueueCapacity(400);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("큐");
    }

    @Test
    void TC_기본값의_처리량_상한이_초당_200건이다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();

        // 상한 = 배치 ÷ 주기. 이 값이 낮으면 워커·슬롯이 아무리 넉넉해도 그만큼밖에 못 돈다.
        double perSecond = props.getBatchSize() / (props.getTickMs() / 1000.0);

        assertThat(perSecond).isEqualTo(200.0);
    }

    @Test
    void TC_provider_슬롯_수가_0이면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setProviderPermits(0);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_워커는_16_버스_슬롯은_예전_실효값_4_지하철_슬롯은_16() {
        // #427 처방 2차: 워커를 늘려도 버스(한도 있는 공급자) 동시 호출은 예전(워커 4 = 동시 4) 그대로
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();

        assertThat(props.getPoolSize()).isEqualTo(16);
        assertThat(props.getProviderPermits()).isEqualTo(4);
        assertThat(props.getSubwayProviderPermits()).isEqualTo(16);
        assertThat(props.getSlotRetrySeconds()).isEqualTo(2);
    }

    @Test
    void TC_application_yaml_의_워커와_슬롯이_바인딩된다() {
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withInitializer(new org.springframework.boot.test.context.ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .run(context -> {
                    JourneyEvaluatorProperties props = context.getBean(JourneyEvaluatorProperties.class);
                    assertThat(props.getPoolSize()).isEqualTo(16);
                    assertThat(props.getProviderPermits()).isEqualTo(4);
                    assertThat(props.getSubwayProviderPermits()).isEqualTo(16);
                    assertThat(props.getSlotRetrySeconds()).isEqualTo(2);
                });
    }

    @Test
    void TC_워커_16_과_함께_DB_풀은_20() throws Exception {
        // 10 이면 지하철 사다리 6,000 에서 커넥션 대기 p95 373ms. 시험 설정(application.properties)이 5 로 덮으므로
        // 운영 yaml 을 직접 읽는다.
        var sources = new org.springframework.boot.env.YamlPropertySourceLoader()
                .load("main", new org.springframework.core.io.ClassPathResource("application.yaml"));
        Object value = sources.get(0).getProperty("spring.datasource.hikari.maximum-pool-size");
        assertThat(String.valueOf(value)).isEqualTo("${DB_POOL_MAX_SIZE:20}");
    }

    @org.springframework.boot.context.properties.EnableConfigurationProperties(JourneyEvaluatorProperties.class)
    static class Config {
    }

    @Test
    void TC_지하철_슬롯이나_재시도_간격이_0이면_부팅을_막는다() {
        JourneyEvaluatorProperties noSubway = new JourneyEvaluatorProperties();
        noSubway.setSubwayProviderPermits(0);
        JourneyEvaluatorProperties noRetry = new JourneyEvaluatorProperties();
        noRetry.setSlotRetrySeconds(0);

        assertThatThrownBy(noSubway::validate).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(noRetry::validate).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void TC_배수가_1보다_작으면_부팅을_막는다() {
        JourneyEvaluatorProperties props = new JourneyEvaluatorProperties();
        props.setUnchangedBackoffMultiplier(0);

        assertThatThrownBy(props::validate).isInstanceOf(IllegalStateException.class);
    }
}
