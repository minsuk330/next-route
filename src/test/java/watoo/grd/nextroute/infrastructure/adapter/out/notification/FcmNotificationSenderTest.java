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
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 부분 실패를 어떻게 접느냐 — 이 클래스의 전부다.
 *
 * <p>두 가지 사고를 여기서 막는다. 성공 하나로 행을 끝내면 <b>일시 실패한 기기가 영원히 못
 * 받고</b>, 아무 실패 코드로나 token 을 끄면 <b>설정 실수 한 번이 주소록을 지운다</b>. 둘 다
 * 운영에서는 "알림이 안 오네요"로만 나타난다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FCM 발송 결과 분류")
class FcmNotificationSenderTest {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private static final long USER_ID = 42L;

    @Mock UserPushTokenRepository tokenRepository;
    @Mock PushTokenDeactivationService deactivationService;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private StubMessagingClient messagingClient;
    private FcmNotificationSender sender;
    private JourneyNotificationProperties properties;

    @BeforeEach
    void setUp() {
        properties = new JourneyNotificationProperties();
        Clock clock = Clock.fixed(NOW, ZoneId.of("Asia/Seoul"));
        messagingClient = new StubMessagingClient();
        sender = new FcmNotificationSender(tokenRepository, messagingClient,
                new FcmMessageFactory(properties), deactivationService,
                new JourneyNotificationMetrics(registry), clock);
    }

    // ── 종결 규칙 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("성공만 있으면 정상 반환이다")
    void allSuccessIsSent() {
        givenTokens(2);
        messagingClient.results(PushResult.ok(), PushResult.ok());

        assertThatCode(() -> sender.send(command())).doesNotThrowAnyException();
        assertThat(deactivated()).isEmpty();
    }

    @Test
    @DisplayName("성공이 섞여 있어도 일시 실패가 있으면 재시도한다")
    void transientBeatsSuccess() {
        givenTokens(2);
        messagingClient.results(PushResult.ok(), PushResult.failed("UNAVAILABLE"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("SENT 로 끝내면 그 기기는 영원히 못 받는다")
                .isInstanceOf(NotificationSendException.class)
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    @Test
    @DisplayName("성공 + UNREGISTERED + 일시 실패면 재시도하되 죽은 token 은 그때 끈다")
    void mixedOutcomeRetriesAndDeactivatesOnlyUnregistered() {
        List<UserPushToken> tokens = givenTokens(3);
        messagingClient.results(PushResult.ok(), PushResult.failed("UNREGISTERED"),
                PushResult.failed("UNAVAILABLE"));

        assertThatThrownBy(() -> sender.send(command()))
                .isInstanceOf(NotificationSendException.class)
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
        assertThat(deactivated()).extracting(ObservedToken::id)
                .containsExactly(tokens.get(1).getId());
    }

    @Test
    @DisplayName("전부 UNREGISTERED 면 영구 실패이고 전부 끄기로 넘어간다")
    void allUnregisteredIsPermanent() {
        List<UserPushToken> tokens = givenTokens(2);
        messagingClient.results(PushResult.failed("UNREGISTERED"),
                PushResult.failed("UNREGISTERED"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("아무 기기도 못 받았다. SENT 로 찍으면 지표가 거짓말을 한다")
                .isInstanceOf(NotificationSendException.class)
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);
        assertThat(deactivated()).extracting(ObservedToken::id)
                .containsExactly(tokens.get(0).getId(), tokens.get(1).getId());
    }

    @Test
    @DisplayName("전부 일시 실패면 재시도한다")
    void allTransientRetries() {
        givenTokens(2);
        messagingClient.results(PushResult.failed("UNAVAILABLE"),
                PushResult.failed("QUOTA_EXCEEDED"));

        assertThatThrownBy(() -> sender.send(command()))
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
        assertThat(deactivated()).isEmpty();
    }

    @Test
    @DisplayName("모르는 코드는 일시 실패로 본다")
    void unknownCodeIsTransient() {
        givenTokens(1);
        messagingClient.results(PushResult.failed("SOMETHING_NEW"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("영구로 단정하면 알림이 조용히 사라진다")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
    }

    @Test
    @DisplayName("살아 있는 token 이 없으면 영구 실패다")
    void noActiveTokenIsPermanent() {
        given(tokenRepository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(anyLong()))
                .willReturn(List.of());

        assertThatThrownBy(() -> sender.send(command()))
                .hasMessage("no-active-token")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);
        assertThat(registry.counter(JourneyNotificationMetrics.NO_ACTIVE_TOKEN,
                "channel", "PUSH").count())
                .as("provider 실패와 섞으면 정상 token churn 이 실패 지표를 부풀린다")
                .isEqualTo(1);
        verify(deactivationService, never()).deactivateIfSafe(anyList());
    }

    // ── 비활성화 대상 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("INVALID_ARGUMENT 는 영구 실패지만 token 은 살려 둔다")
    void invalidArgumentDoesNotDeactivate() {
        givenTokens(1);
        messagingClient.results(PushResult.failed("INVALID_ARGUMENT"));

        assertThatThrownBy(() -> sender.send(command()))
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(true);
        assertThat(deactivated())
                .as("payload·TTL·크기 문제로도 나는 코드다. 끄면 사고 한 번이 주소록을 지운다")
                .isEmpty();
    }

    @Test
    @DisplayName("SENDER_ID_MISMATCH 도 token 을 끄지 않는다")
    void senderIdMismatchDoesNotDeactivate() {
        givenTokens(2);
        messagingClient.results(PushResult.failed("SENDER_ID_MISMATCH"),
                PushResult.failed("SENDER_ID_MISMATCH"));

        assertThatThrownBy(() -> sender.send(command()));

        assertThat(deactivated())
                .as("서버 자격증명이 틀린 것이지 token 이 죽은 것이 아니다")
                .isEmpty();
    }

    @Test
    @DisplayName("THIRD_PARTY_AUTH_ERROR 도 token 을 끄지 않는다")
    void apnsAuthErrorDoesNotDeactivate() {
        givenTokens(1);
        messagingClient.results(PushResult.failed("THIRD_PARTY_AUTH_ERROR"));

        assertThatThrownBy(() -> sender.send(command()));

        assertThat(deactivated()).as("APNs 키 문제다").isEmpty();
    }

    @Test
    @DisplayName("끌 token 은 상한 판단을 하는 쪽으로 통째로 넘긴다")
    void deactivationCapIsDelegated() {
        int limit = properties.getFcm().getDeactivateMaxPerSend();
        givenTokens(limit + 1);
        messagingClient.results(IntStream.rangeClosed(0, limit)
                .mapToObj(i -> PushResult.failed("UNREGISTERED"))
                .toArray(PushResult[]::new));

        assertThatThrownBy(() -> sender.send(command()));

        // 실제로 하나도 끄지 않는 것은 PushTokenDeactivationService 가 한다(그쪽 테스트가 고정).
        assertThat(deactivated()).hasSize(limit + 1);
    }

    // ── 순서와 개인정보 ──────────────────────────────────────────────────────

    @Test
    @DisplayName("결과는 token 순서와 짝지어진다 — 성공한 행에서도 죽은 token 은 꺼진다")
    void resultsPairWithTokensInOrder() {
        List<UserPushToken> tokens = givenTokens(3);
        messagingClient.results(PushResult.ok(), PushResult.ok(),
                PushResult.failed("UNREGISTERED"));

        assertThatCode(() -> sender.send(command()))
                .as("일시 실패가 없고 성공이 있으니 행은 SENT 다")
                .doesNotThrowAnyException();

        assertThat(messagingClient.lastTokens)
                .isEqualTo(tokens.stream().map(UserPushToken::getToken).toList());
        assertThat(deactivated()).extracting(ObservedToken::id)
                .as("어긋나면 멀쩡한 기기가 꺼지고 죽은 기기는 남는다")
                .containsExactly(tokens.get(2).getId());
    }

    @Test
    @DisplayName("비활성화 조건에는 발송 시점에 관측한 소유자·lastSeenAt 이 실린다")
    void deactivationCarriesObservedIdentity() {
        List<UserPushToken> tokens = givenTokens(1);
        messagingClient.results(PushResult.failed("UNREGISTERED"));

        assertThatThrownBy(() -> sender.send(command()));

        assertThat(deactivated()).containsExactly(new ObservedToken(
                tokens.get(0).getId(), USER_ID, tokens.get(0).getLastSeenAt()));
    }

    @Test
    @DisplayName("결과 길이가 어긋나면 아무것도 끄지 않고 재시도한다")
    void lengthMismatchIsTransientAndDeactivatesNothing() {
        givenTokens(3);
        messagingClient.results(PushResult.ok());

        assertThatThrownBy(() -> sender.send(command()))
                .hasMessageContaining("fcm-result-mismatch")
                .extracting(e -> ((NotificationSendException) e).isPermanent())
                .isEqualTo(false);
        verify(deactivationService, never()).deactivateIfSafe(anyList());
    }

    @Test
    @DisplayName("예외 문구에 token 문자열이 없다")
    void exceptionMessageCarriesNoToken() {
        givenTokens(2);
        messagingClient.results(PushResult.ok(), PushResult.failed("UNAVAILABLE"));

        assertThatThrownBy(() -> sender.send(command()))
                .as("실패는 개수만 센다(REQUIREMENTS §6)")
                .hasMessageNotContaining("fcm-token")
                .hasMessageContaining("success=1")
                .hasMessageContaining("transient=1");
    }

    @Test
    @DisplayName("기기 단위 결과를 행 지표와 따로 센다")
    void perTokenMetricsAreSeparate() {
        givenTokens(3);
        given(deactivationService.deactivateIfSafe(anyList())).willReturn(1);
        messagingClient.results(PushResult.ok(), PushResult.failed("UNAVAILABLE"),
                PushResult.failed("UNREGISTERED"));

        assertThatThrownBy(() -> sender.send(command()));

        assertThat(pushTokens("success")).isEqualTo(1);
        assertThat(pushTokens("transient")).isEqualTo(1);
        assertThat(pushTokens("permanent")).isEqualTo(1);
        assertThat(registry.counter(JourneyNotificationMetrics.TOKEN_DEACTIVATED).count())
                .isEqualTo(1);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ObservedToken> deactivated() {
        ArgumentCaptor<List<ObservedToken>> captor = ArgumentCaptor.forClass(List.class);
        verify(deactivationService, org.mockito.Mockito.atMost(1))
                .deactivateIfSafe(captor.capture());
        return captor.getAllValues().isEmpty() ? List.of() : captor.getValue();
    }

    private double pushTokens(String outcome) {
        return registry.counter(JourneyNotificationMetrics.PUSH_TOKENS, "outcome", outcome).count();
    }

    private List<UserPushToken> givenTokens(int count) {
        List<UserPushToken> tokens = IntStream.rangeClosed(1, count)
                .mapToObj(i -> token(i, "fcm-token-" + i))
                .toList();
        given(tokenRepository.findByUserIdAndEnabledTrueAndDeletedAtIsNull(USER_ID))
                .willReturn(tokens);
        return tokens;
    }

    private UserPushToken token(long id, String value) {
        UserPushToken token = UserPushToken.builder()
                .userId(USER_ID)
                .platform(PushPlatform.ANDROID)
                .provider(PushProvider.FCM)
                .token(value)
                .environment(PushEnvironment.PRODUCTION)
                .lastSeenAt(NOW.minusSeconds(600))
                .build();
        ReflectionTestUtils.setField(token, "id", id);
        return token;
    }

    private NotificationSendCommand command() {
        return new NotificationSendCommand(7L, USER_ID, 11L, 1,
                JourneyNotificationScope.TRANSFER, 0, JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                NotificationChannel.PUSH, 1L, 1L, null, NOW.plusSeconds(600));
    }

    /** 심어 둔 결과를 그대로 돌려준다. 받은 token 순서도 기록한다. */
    private static class StubMessagingClient implements FirebaseMessagingClient {

        private List<PushResult> results = List.of();
        private List<String> lastTokens = List.of();

        void results(PushResult... results) {
            this.results = List.of(results);
        }

        @Override
        public List<PushResult> send(PushMessage message, List<String> tokens) {
            this.lastTokens = tokens;
            return results;
        }
    }
}
