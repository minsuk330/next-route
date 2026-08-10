package watoo.grd.nextroute.infrastructure.adapter.out.notification;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.exception.NotificationSendException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient;
import watoo.grd.nextroute.application.journey.port.out.FirebaseMessagingClient.PushResult;
import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService.ObservedToken;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 실패 코드를 어느 칸에 넣느냐 — 이 클래스의 전부다.
 *
 * <p>두 가지가 조용히 망가진다. 모르는 코드를 영구 실패로 단정하면 <b>알림이 사라지고</b>,
 * 아무 실패 코드로나 token 을 끄면 <b>설정 실수 한 번이 주소록을 지운다</b>. 둘 다 운영에서는
 * "알림이 안 오네요"로만 나타난다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FCM 발송 결과 분류")
class FcmNotificationSenderTest {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private static final long USER_ID = 42L;
    private static final long TOKEN_ID = 7L;
    private static final Instant LAST_SEEN_AT = NOW.minusSeconds(600);

    @Mock UserPushTokenRepository tokenRepository;
    @Mock PushTokenDeactivationService deactivationService;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private StubMessagingClient messagingClient;
    private FcmNotificationSender sender;

    @BeforeEach
    void setUp() {
        JourneyNotificationProperties properties = new JourneyNotificationProperties();
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        messagingClient = new StubMessagingClient();
        sender = new FcmNotificationSender(tokenRepository, messagingClient,
                new FcmMessageFactory(properties), deactivationService,
                new JourneyNotificationMetrics(registry), clock);
    }

    // ── 종결 규칙 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("접수되면 정상 반환이고 아무 token 도 끄지 않는다")
    void successReturnsQuietly() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.ok());

        assertThatCode(() -> sender.send(command())).doesNotThrowAnyException();

        assertThat(messagingClient.lastToken).isEqualTo("fcm-token-1");
        verify(deactivationService, never()).deactivateIfSafe(any());
    }

    @Test
    @DisplayName("살아 있는 token 이 없으면 영구 실패이고 FCM 을 부르지 않는다")
    void noActiveTokenIsPermanent() {
        given(tokenRepository
                .findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                        anyLong()))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> sender.send(command()))
                .hasMessage("no-active-token")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);

        assertThat(messagingClient.callCount).isZero();
        assertThat(registry.counter(JourneyNotificationMetrics.NO_ACTIVE_TOKEN,
                "channel", "PUSH").count())
                .as("provider 실패와 섞으면 정상 token churn 이 실패 지표를 부풀린다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("UNAVAILABLE 은 일시 실패다")
    void providerOutageIsTransient() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed("UNAVAILABLE"));

        assertThatThrownBy(() -> sender.send(command()))
                .isInstanceOf(NotificationSendException.class)
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    @Test
    @DisplayName("모르는 코드는 일시 실패로 본다")
    void unknownCodeIsTransient() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed("SOMETHING_NEW"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("영구로 단정하면 알림이 조용히 사라진다")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    @Test
    @DisplayName("코드가 아예 없어도 일시 실패다")
    void missingCodeIsTransient() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed(null));

        assertThatThrownBy(() -> sender.send(command()))
                .hasMessageContaining("UNKNOWN")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    // ── 비활성화 대상 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("UNREGISTERED 는 영구 실패이고 그 token 을 끈다")
    void unregisteredDeactivatesToken() {
        givenToken("fcm-token-1");
        given(deactivationService.deactivateIfSafe(any())).willReturn(1);
        messagingClient.result(PushResult.failed("UNREGISTERED"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("사용자는 아무것도 못 받았다. SENT 로 찍으면 지표가 거짓말을 한다")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);

        assertThat(registry.counter(JourneyNotificationMetrics.TOKEN_DEACTIVATED).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("비활성화 조건에는 발송 시점에 관측한 소유자·lastSeenAt 이 실린다")
    void deactivationCarriesObservedIdentity() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed("UNREGISTERED"));

        assertThatThrownBy(() -> sender.send(command()));

        ArgumentCaptor<ObservedToken> captor = ArgumentCaptor.forClass(ObservedToken.class);
        verify(deactivationService).deactivateIfSafe(captor.capture());
        assertThat(captor.getValue())
                .as("그 사이 재등록된 행을 꺼 버리지 않으려면 관측값이 조건에 들어가야 한다")
                .isEqualTo(new ObservedToken(TOKEN_ID, USER_ID, LAST_SEEN_AT));
    }

    @Test
    @DisplayName("INVALID_ARGUMENT 는 영구 실패지만 token 은 살려 둔다")
    void invalidArgumentDoesNotDeactivate() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed("INVALID_ARGUMENT"));

        assertThatThrownBy(() -> sender.send(command()))
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);

        verify(deactivationService, never()).deactivateIfSafe(any());
    }

    @Test
    @DisplayName("SENDER_ID_MISMATCH·THIRD_PARTY_AUTH_ERROR 도 token 을 끄지 않는다")
    void configurationErrorsDoNotDeactivate() {
        givenToken("fcm-token-1");
        messagingClient.result(PushResult.failed("SENDER_ID_MISMATCH"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("서버 자격증명이 틀린 것이지 token 이 죽은 것이 아니다")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);

        verify(deactivationService, never()).deactivateIfSafe(any());
    }

    // ── 개인정보 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("예외 문구에는 코드만 담고 token 문자열은 없다")
    void exceptionMessageCarriesNoToken() {
        givenToken("fcm-token-secret");
        messagingClient.result(PushResult.failed("UNAVAILABLE"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("token 은 로그·지표·예외 어디에도 실으면 안 된다(REQUIREMENTS §6)")
                .hasMessage("fcm: UNAVAILABLE")
                .hasMessageNotContaining("fcm-token-secret");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private void givenToken(String value) {
        UserPushToken token = UserPushToken.builder()
                .userId(USER_ID)
                .platform(PushPlatform.ANDROID)
                .provider(PushProvider.FCM)
                .token(value)
                .environment(PushEnvironment.PRODUCTION)
                .lastSeenAt(LAST_SEEN_AT)
                .build();
        ReflectionTestUtils.setField(token, "id", TOKEN_ID);
        given(tokenRepository
                .findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                        USER_ID))
                .willReturn(Optional.of(token));
    }

    private NotificationSendCommand command() {
        return new NotificationSendCommand(7L, USER_ID, 11L, 1,
                JourneyNotificationScope.TRANSFER, 0, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                NotificationChannel.PUSH, 1L, 1L, null, NOW.plusSeconds(600));
    }

    /** 심어 둔 결과를 그대로 돌려주고 받은 token 을 기록한다. */
    private static class StubMessagingClient implements FirebaseMessagingClient {

        private PushResult result = PushResult.ok();
        private String lastToken;
        private int callCount;

        void result(PushResult result) {
            this.result = result;
        }

        @Override
        public PushResult send(PushMessage message, String token) {
            callCount++;
            lastToken = token;
            return result;
        }
    }
}
