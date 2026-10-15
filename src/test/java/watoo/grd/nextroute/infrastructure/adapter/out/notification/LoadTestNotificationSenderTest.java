package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyRelaySenderGuard;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.NotificationSender;
import watoo.grd.nextroute.application.journey.port.out.PushMessage.Delivery;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 부하 실험용 가짜 발송처(#427)가 운영 배선에 섞이지 않는지, 그리고 받은 것을 제대로 세는지.
 *
 * <p>배선은 실제 조건부 빈 다섯(no-op·가짜·FCM·FCM 조립기·relay 가드)을 그대로 올려 프로필·sender 조합별로
 * 어느 발송처가 뜨는지 본다. 외부 의존(토큰 저장소·FCM 창구 등)만 대역이다.
 */
@DisplayName("부하 실험용 가짜 발송처")
class LoadTestNotificationSenderTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(NoOpNotificationSender.class, LoadTestNotificationSender.class,
                    FcmNotificationSender.class, FcmMessageFactory.class, JourneyRelaySenderGuard.class)
            .withBean(JourneyNotificationProperties.class, JourneyNotificationProperties::new)
            .withBean(UserPushTokenRepository.class, () -> mock(UserPushTokenRepository.class))
            .withBean(FirebaseMessagingClient.class, () -> mock(FirebaseMessagingClient.class))
            .withBean(PushTokenDeactivationService.class, () -> mock(PushTokenDeactivationService.class))
            .withBean(JourneyNotificationMetrics.class, () -> mock(JourneyNotificationMetrics.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(Clock.class, Clock::systemUTC);

    @Nested
    @DisplayName("배선")
    class Wiring {

        @Test
        @DisplayName("loadtest 프로필이 없으면 sender=loadtest 여도 가짜 빈이 없다 — 발송처가 하나도 없어 앱 부팅이 죽는다")
        void absentWithoutProfile() {
            runner.withPropertyValues("journey.notification.sender=loadtest")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(LoadTestNotificationSender.class);
                        assertThat(context).doesNotHaveBean(NotificationSender.class);
                    });
        }

        @Test
        @DisplayName("loadtest 프로필이어도 sender 를 loadtest 로 고르지 않으면 가짜 빈이 없다")
        void absentWithProfileButOtherSender() {
            runner.withPropertyValues("spring.profiles.active=loadtest")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(LoadTestNotificationSender.class);
                        assertThat(context).getBean(NotificationSender.class)
                                .isInstanceOf(NoOpNotificationSender.class);
                    });
        }

        @Test
        @DisplayName("loadtest 프로필 + sender=loadtest 면 가짜가 유일한 발송처이고 relay 가드를 통과한다")
        void presentWithProfileAndSender() {
            runner.withPropertyValues("spring.profiles.active=loadtest,worker",
                            "journey.notification.sender=loadtest",
                            "journey.notification.relay.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).getBean(NotificationSender.class)
                                .isInstanceOf(LoadTestNotificationSender.class);
                        assertThat(context.getBean(NotificationSender.class).deliversRealMessages()).isTrue();
                        assertThat(context).hasSingleBean(JourneyRelaySenderGuard.class);
                    });
        }

        @Test
        @DisplayName("운영처럼 worker,prod + sender=fcm 이면 그대로 FCM 이다")
        void productionWorkerStillPicksFcm() {
            runner.withPropertyValues("spring.profiles.active=worker,prod",
                            "journey.notification.sender=fcm",
                            "journey.notification.relay.enabled=true")
                    .run(context -> {
                        assertThat(context).hasNotFailed();
                        assertThat(context).getBean(NotificationSender.class)
                                .isInstanceOf(FcmNotificationSender.class);
                        assertThat(context).doesNotHaveBean(LoadTestNotificationSender.class);
                    });
        }

        @Test
        @DisplayName("운영 api 처럼 sender 를 안 주면 그대로 no-op 이다")
        void productionApiStillPicksNoop() {
            runner.withPropertyValues("spring.profiles.active=prod")
                    .run(context -> assertThat(context).getBean(NotificationSender.class)
                            .isInstanceOf(NoOpNotificationSender.class));
        }

        @Test
        @DisplayName("prod 프로필과 함께 켜면 부팅을 막는다")
        void refusesWithProd() {
            runner.withPropertyValues("spring.profiles.active=loadtest,prod",
                            "journey.notification.sender=loadtest")
                    .run(context -> assertThat(context).getFailure()
                            .hasStackTraceContaining("prod 프로필과 함께 뜰 수 없다"));
        }
    }

    @Nested
    @DisplayName("수신 집계")
    class Counting {

        private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        private final LoadTestNotificationSender sender = newSender(registry);

        @Test
        @DisplayName("같은 알림 행 번호를 두 번 받으면 번호 중복으로 센다")
        void duplicateById() {
            NotificationSendCommand command = command(1L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 3);
            sender.record(command, Delivery.ALERTING);
            sender.record(command, Delivery.ALERTING);

            assertThat(registry.counter(LoadTestNotificationSender.DUPLICATE_ID).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("행 번호가 달라도 업무 키(여정·버전·종류·환승·평가 번호·울림)가 같으면 업무 중복으로 센다")
        void duplicateByBusinessKey() {
            sender.record(command(1L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 3), Delivery.QUIET);
            sender.record(command(2L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 3), Delivery.QUIET);
            sender.record(command(3L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 4), Delivery.QUIET);

            assertThat(registry.counter(LoadTestNotificationSender.DUPLICATE_ID).count()).isZero();
            assertThat(registry.counter(LoadTestNotificationSender.DUPLICATE_BUSINESS).count()).isEqualTo(1);
        }

        @Test
        @DisplayName("한 경로 버전에 정책 대상 울림이 두 번이면 그 버전을 한 번 센다 — 접근 알림·조용한 갱신·다른 버전은 빼고")
        void alertingPerRevision() {
            sender.record(command(1L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 1), Delivery.ALERTING);
            sender.record(command(2L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 2), Delivery.QUIET);
            sender.record(command(3L, 10L, 1, JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, 2), null);
            sender.record(command(4L, 10L, 2, JourneyCheckpoint.TRANSFER_RISK_MISSED, 3), Delivery.ALERTING);
            assertThat(sender.revisionsWithMoreThanOneAlerting()).isZero();

            sender.record(command(5L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_MISSED, 4), Delivery.ALERTING);
            sender.record(command(6L, 10L, 1, JourneyCheckpoint.REROUTE_SUGGESTED, 5), Delivery.ALERTING);
            assertThat(sender.revisionsWithMoreThanOneAlerting()).isEqualTo(1);
        }

        @Test
        @DisplayName("받은 수를 종류·울림별로 센다")
        void receivedByCheckpointAndDelivery() {
            sender.record(command(1L, 10L, 1, JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 1), Delivery.ALERTING);
            sender.record(command(2L, 11L, 1, JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, 1), null);

            assertThat(registry.counter(LoadTestNotificationSender.RECEIVED,
                    "checkpoint", "TRANSFER_RISK_AT_RISK", "delivery", "ALERTING").count()).isEqualTo(1);
            assertThat(registry.counter(LoadTestNotificationSender.RECEIVED,
                    "checkpoint", "TRANSFER_TARGET_APPROACHING", "delivery", "none").count()).isEqualTo(1);
        }
    }

    private static LoadTestNotificationSender newSender(MeterRegistry registry) {
        MockEnvironment environment = new MockEnvironment();
        return new LoadTestNotificationSender(mock(UserPushTokenRepository.class),
                new JourneyNotificationProperties(), registry, environment, Clock.systemUTC());
    }

    private static NotificationSendCommand command(long id, long journeyId, int revision,
                                                   JourneyCheckpoint checkpoint, long evaluationVersion) {
        return new NotificationSendCommand(id, 100L, journeyId, revision, JourneyNotificationScope.TRANSFER,
                0, checkpoint, NotificationChannel.PUSH, 1L, evaluationVersion, null,
                Instant.now().plusSeconds(300));
    }
}
