package watoo.grd.nextroute.application.push.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService.ObservedToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 발송 시점에 <b>관측한</b> 신원을 조건으로 넘기는지. 그것이 없으면 발송 도중 재등록된 기기를
 * 꺼 버린다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("push token 비활성화")
class PushTokenDeactivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final Instant SEEN_AT = NOW.minusSeconds(3600);

    @Mock UserPushTokenRepository repository;

    private PushTokenDeactivationService service;

    @BeforeEach
    void setUp() {
        service = new PushTokenDeactivationService(repository, Clock.fixed(NOW, KST));
    }

    @Test
    @DisplayName("관측한 소유자와 lastSeenAt 을 조건으로 넘긴다")
    void passesObservedIdentityAsCondition() {
        given(repository.deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class))).willReturn(1);

        int deactivated = service.deactivateIfSafe(new ObservedToken(7L, 100L, SEEN_AT));

        assertThat(deactivated).isEqualTo(1);
        verify(repository).deactivate(7L, 100L, SEEN_AT, LocalDateTime.now(Clock.fixed(NOW, KST)));
    }

    @Test
    @DisplayName("0행은 정상이다 — 더 새로운 등록이 이긴 것이다")
    void zeroAffectedRowsIsNormal() {
        given(repository.deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class))).willReturn(0);

        assertThat(service.deactivateIfSafe(new ObservedToken(7L, 100L, SEEN_AT))).isZero();
    }
}
