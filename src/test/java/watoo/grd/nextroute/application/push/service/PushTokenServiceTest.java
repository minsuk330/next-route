package watoo.grd.nextroute.application.push.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.domain.push.entity.PushPlatform;
import watoo.grd.nextroute.domain.push.entity.PushProvider;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("push token 서비스")
class PushTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-06T04:00:00Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final long USER_ID = 42L;
    private static final String TOKEN = "fcm-token-eXy3";

    @Mock UserPushTokenRepository repository;

    private PushTokenService service;

    @BeforeEach
    void setUp() {
        service = new PushTokenService(repository, Clock.fixed(NOW, KST));
    }

    @Test
    @DisplayName("등록은 JWT 의 userId 와 command 값만 저장소로 넘긴다")
    void registerPassesOnlyAuthenticatedIdentity() {
        given(repository.register(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), any(), any(LocalDateTime.class))).willReturn(1L);

        service.register(USER_ID, new RegisterPushTokenCommand(
                TOKEN, PushPlatform.IOS, PushProvider.FCM, PushEnvironment.DEVELOPMENT));

        ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Instant> seenAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).register(userId.capture(), eqStr("IOS"), eqStr("FCM"), eqStr(TOKEN),
                eqStr("DEVELOPMENT"), seenAt.capture(), isNull(), any(LocalDateTime.class));
        assertThat(userId.getValue()).isEqualTo(USER_ID);
        assertThat(seenAt.getValue()).as("시각은 고정 Clock 에서 온다").isEqualTo(NOW);
    }

    @Test
    @DisplayName("등록의 감사 시각도 같은 Clock 을 쓴다")
    void registerUsesInjectedClockForAuditColumns() {
        given(repository.register(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), any(), any(LocalDateTime.class))).willReturn(1L);

        service.register(USER_ID, new RegisterPushTokenCommand(
                TOKEN, PushPlatform.ANDROID, PushProvider.FCM, PushEnvironment.PRODUCTION));

        ArgumentCaptor<LocalDateTime> now = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).register(anyLong(), anyString(), anyString(), anyString(), anyString(),
                any(Instant.class), any(), now.capture());
        assertThat(now.getValue()).isEqualTo(LocalDateTime.ofInstant(NOW, KST));
    }

    @Test
    @DisplayName("해제는 0행이어도 예외를 내지 않는다 — 멱등이어야 한다")
    void unregisterIsIdempotent() {
        given(repository.unregister(USER_ID, TOKEN, LocalDateTime.ofInstant(NOW, KST)))
                .willReturn(0);

        assertThatCode(() -> service.unregister(USER_ID, TOKEN)).doesNotThrowAnyException();

        verify(repository).unregister(USER_ID, TOKEN, LocalDateTime.ofInstant(NOW, KST));
    }

    private String eqStr(String value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }
}
