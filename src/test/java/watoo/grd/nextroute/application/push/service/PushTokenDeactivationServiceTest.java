package watoo.grd.nextroute.application.push.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.push.service.PushTokenDeactivationService.ObservedToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 주소록을 지우는 사고를 막는 자리.
 *
 * <p>한 번의 발송에서 수십 개의 token 이 동시에 죽었다는 답이 오면 그것은 token 문제가 아니라
 * 우리 쪽 사고다(자격증명 교체, payload 오류). 그때 시키는 대로 다 끄면 되돌릴 방법이 없다.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("push token 비활성화")
class PushTokenDeactivationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T03:00:00Z");
    private static final Instant SEEN_AT = NOW.minusSeconds(3600);

    @Mock UserPushTokenRepository repository;

    private JourneyNotificationProperties properties;
    private PushTokenDeactivationService service;

    @BeforeEach
    void setUp() {
        properties = new JourneyNotificationProperties();
        service = new PushTokenDeactivationService(repository, properties,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private List<ObservedToken> observed(int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> new ObservedToken((long) i, 100L, SEEN_AT))
                .toList();
    }

    @Test
    @DisplayName("관측한 소유자와 lastSeenAt 을 조건으로 넘긴다")
    void passesObservedIdentityAsCondition() {
        given(repository.deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class))).willReturn(1);

        int deactivated = service.deactivateIfSafe(observed(1));

        assertThat(deactivated).isEqualTo(1);
        verify(repository).deactivate(1L, 100L, SEEN_AT, LocalDateTime.now(
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul"))));
    }

    @Test
    @DisplayName("0행은 정상이다 — 더 새로운 등록이 이긴 것이다")
    void zeroAffectedRowsIsNormal() {
        given(repository.deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class))).willReturn(0);

        assertThat(service.deactivateIfSafe(observed(2))).isZero();
    }

    @Test
    @DisplayName("상한을 넘으면 하나도 끄지 않는다")
    void overLimitDeactivatesNothing() {
        int limit = properties.getFcm().getDeactivateMaxPerSend();

        int deactivated = service.deactivateIfSafe(observed(limit + 1));

        assertThat(deactivated).isZero();
        verify(repository, never()).deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class));
    }

    @Test
    @DisplayName("상한까지는 그대로 끈다")
    void atLimitStillDeactivates() {
        int limit = properties.getFcm().getDeactivateMaxPerSend();
        given(repository.deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class))).willReturn(1);

        assertThat(service.deactivateIfSafe(observed(limit))).isEqualTo(limit);
    }

    @Test
    @DisplayName("빈 목록이면 저장소를 부르지 않는다")
    void emptyListTouchesNothing() {
        assertThat(service.deactivateIfSafe(List.of())).isZero();

        verify(repository, never()).deactivate(anyLong(), anyLong(), any(Instant.class),
                any(LocalDateTime.class));
    }
}
