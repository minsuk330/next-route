package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import com.google.firebase.messaging.FirebaseMessaging;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 발송 창구가 <b>스프링 컨텍스트에서 실제로 만들어지는지</b>.
 *
 * <p>단위 시험({@link FirebaseAdminMessagingClientTest})은 생성자를 직접 부르므로 이 경로를
 * 밟지 않는다. 그래서 이 클래스가 없으면 다음 사고가 다시 난다 — {@code sender=noop} 이던
 * 동안 이 빈은 아예 뜨지 않았고, 운영에서 {@code fcm} 으로 켜는 순간
 * {@code No default constructor found} 로 <b>부팅이 통째로 죽었다</b>.
 *
 * <p>원인은 <b>생성자가 둘</b>이라는 것이었다. Spring 은 후보가 여럿이면 {@code @Autowired}
 * 표시를 찾고, 없으면 인자 없는 생성자로 폴백한다. 시험용 생성자의
 * {@code MessageDispatcher} 가 빈이 아니라는 사실을 근거로 나머지 하나를 골라 주지 않는다.
 */
@DisplayName("FCM 발송 창구 배선")
class FirebaseAdminMessagingClientWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(FirebaseAdminMessagingClient.class)
            .withBean(FirebaseMessaging.class, () -> mock(FirebaseMessaging.class))
            .withBean(JourneyNotificationProperties.class, JourneyNotificationProperties::new);

    @Test
    @DisplayName("sender 가 fcm 이면 창구 빈이 만들어진다 — 생성자가 둘이어도")
    void createsClientWhenSenderIsFcm() {
        runner.withPropertyValues("journey.notification.sender=fcm")
                .run(context -> {
                    assertThat(context)
                            .as("조용한 무발송보다 나쁜 것은 부팅 실패다. 둘 다 아니어야 한다")
                            .hasNotFailed();
                    assertThat(context).hasSingleBean(FirebaseMessagingClient.class);
                });
    }

    @Test
    @DisplayName("sender 가 noop 이면 창구가 뜨지 않는다 — 자격증명 없이도 개발이 돌아간다")
    void skipsClientWhenSenderIsNoop() {
        runner.withPropertyValues("journey.notification.sender=noop")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FirebaseMessagingClient.class);
                });
    }
}
