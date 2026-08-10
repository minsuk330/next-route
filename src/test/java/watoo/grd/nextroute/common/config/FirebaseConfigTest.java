package watoo.grd.nextroute.common.config;

import com.google.firebase.FirebaseApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 자격증명이 없거나 틀리면 <b>부팅에서</b> 막는다. 켠다고 해 놓고 조용히 아무것도 안 보내는
 * 상태가 가장 나쁘다.
 *
 * <p>반대로 기본값에서는 초기화가 <b>아예 돌지 않아야</b> 한다 — 그래야 자격증명 없이 개발과
 * 테스트가 그대로 돌아간다.
 */
@DisplayName("FCM 초기화 조건")
class FirebaseConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(FirebaseConfig.class);

    @Test
    @DisplayName("sender 가 noop 이면 초기화가 아예 돌지 않는다")
    void noopSenderSkipsFirebase() {
        runner.withPropertyValues("journey.notification.sender=noop")
                .withBean(JourneyNotificationProperties.class, JourneyNotificationProperties::new)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FirebaseApp.class);
                });
    }

    @Test
    @DisplayName("sender 가 fcm 인데 자격증명 파일이 없으면 부팅이 실패한다")
    void missingCredentialsFileFailsStartup() {
        runner.withPropertyValues("journey.notification.sender=fcm")
                .withBean(JourneyNotificationProperties.class, () -> {
                    JourneyNotificationProperties properties = new JourneyNotificationProperties();
                    properties.setSender("fcm");
                    properties.getFcm().setCredentialsPath("/no/such/firebase.json");
                    return properties;
                })
                .run(context -> assertThat(context)
                        .as("조용한 무발송보다 부팅 실패가 낫다")
                        .hasFailed()
                        .getFailure()
                        .hasMessageContaining("FCM 자격증명"));
    }
}
