package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 배포 기본값이 "아무것도 안 함"인지. 이 PR 은 잠든 채 나간다.
 */
@DisplayName("재평가 기본값")
class ReevaluationDefaultsTest {

    @Test
    @DisplayName("application.yaml 의 journey.navigation.reeval 이 바인딩되고 기본은 꺼짐 + 그림자")
    void yamlBindsAndDefaultsToOff() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .run(context -> {
                    ReevaluationProperties properties = context.getBean(ReevaluationProperties.class);
                    assertThat(properties.isEnabled()).isFalse();
                    assertThat(properties.isShadow()).isTrue();
                    assertThat(properties.minGapSeconds(ReevaluationTier.URGENT)).isEqualTo(5);
                    assertThat(properties.minGapSeconds(ReevaluationTier.CAUTION)).isEqualTo(10);
                    assertThat(properties.minGapSeconds(ReevaluationTier.RELAXED)).isEqualTo(30);
                    assertThat(properties.getShedBacklogThreshold()).isEqualTo(200);
                    // #427 처방 2차: 깨우기 상한은 처리량으로 계산, 바닥 = 예전 고정값
                    assertThat(properties.getWakeCapMode())
                            .isEqualTo(ReevaluationProperties.WakeCapMode.CAPACITY);
                    assertThat(properties.getWakeCapMin()).isEqualTo(500);
                    assertThat(properties.getWakeCapMax()).isEqualTo(5000);
                    assertThat(properties.getWakeHorizonSeconds()).isEqualTo(15);
                    assertThat(properties.getWakeHeadroom()).isEqualTo(1.5);
                });
    }

    @Test
    @DisplayName("꺼져 있으면 DB 를 건드리지 않고 받은 여정을 전부 미뤄 돌려준다")
    void disabledTouchesNothing() {
        JourneySessionRepository repository = mock(JourneySessionRepository.class);
        ReevaluationProperties properties = new ReevaluationProperties();
        JdbcReevaluationTrigger trigger = new JdbcReevaluationTrigger(repository, properties,
                new JourneyReevalMetrics(new SimpleMeterRegistry()),
                mock(PlatformTransactionManager.class), Clock.systemUTC());

        TriggerResult result = trigger.request(Map.of(ReevaluationTier.URGENT, Set.of(1L, 2L)));

        assertThat(result.delivered()).isEmpty();
        assertThat(result.deferred()).containsExactlyInAnyOrder(1L, 2L);
        verifyNoInteractions(repository);
    }

    @EnableConfigurationProperties(ReevaluationProperties.class)
    static class Config {
    }
}
