package watoo.grd.nextroute.application.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import watoo.grd.nextroute.application.auth.config.JwtProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.exception.DeviceAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkLimitExceededException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkNotAllowedException;
import watoo.grd.nextroute.application.auth.exception.DeviceSignupRateLimitedException;
import watoo.grd.nextroute.application.auth.exception.InvalidDeviceSecretException;
import watoo.grd.nextroute.application.auth.port.out.DeviceSignupLimitPort;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 기기 익명 계정의 분기 계약(PLAN §2)을 고정한다.
 *
 * <p>가장 중요한 것은 <b>남의 계정에 기기를 붙이지 않는 것</b>이다. 연결은 Bearer 의 주인 계정에만, 애플·카카오가
 * 있는 계정에만, 3대까지만 된다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-10T10:00:00Z");
    private static final String SECRET = "A".repeat(20) + "b-c_d" + "9".repeat(18);   // 43자 base64url
    private static final String OTHER_SECRET = "Z".repeat(43);
    private static final String IP = "203.0.113.7";

    @Mock AuthIdentityRepository identityRepository;
    @Mock UserRepository userRepository;
    @Mock DeviceSignupLimitPort signupLimit;

    private DeviceLoginService service;

    private final List<AuthIdentity> storedIdentities = new ArrayList<>();
    private final List<User> storedUsers = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @BeforeEach
    void setUp() {
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            assignId(user, sequence.incrementAndGet());
            storedUsers.add(user);
            return user;
        });
        when(userRepository.findById(any())).thenAnswer(inv -> storedUsers.stream()
                .filter(u -> inv.getArgument(0).equals(u.getId()))
                .findFirst());
        when(identityRepository.saveAndFlush(any(AuthIdentity.class))).thenAnswer(inv -> {
            AuthIdentity identity = inv.getArgument(0);
            assignId(identity, sequence.incrementAndGet());
            storedIdentities.add(identity);
            return identity;
        });
        when(identityRepository.findByProviderAndProviderSubjectAndDeletedAtIsNull(any(), any()))
                .thenAnswer(inv -> storedIdentities.stream()
                        .filter(i -> i.getProvider() == inv.getArgument(0)
                                && i.getProviderSubject().equals(inv.getArgument(1)))
                        .findFirst());
        when(identityRepository.existsByUserIdAndProviderAndDeletedAtIsNull(any(), any()))
                .thenAnswer(inv -> storedIdentities.stream()
                        .anyMatch(i -> i.getUserId().equals(inv.getArgument(0))
                                && i.getProvider() == inv.getArgument(1)));
        when(identityRepository.countByUserIdAndProviderAndDeletedAtIsNull(any(), any()))
                .thenAnswer(inv -> storedIdentities.stream()
                        .filter(i -> i.getUserId().equals(inv.getArgument(0))
                                && i.getProvider() == inv.getArgument(1))
                        .count());
        when(signupLimit.tryAcquire(anyString())).thenReturn(true);

        JwtProperties jwtProps = new JwtProperties();
        jwtProps.setSecret("test-secret-test-secret-test-secret-0123456789");
        jwtProps.setIssuer("nextroute-test");

        service = new DeviceLoginService(identityRepository, userRepository, signupLimit,
                new JwtProvider(jwtProps), Clock.fixed(NOW, ZoneOffset.UTC), new NoopTransactionManager());
    }

    // ── Bearer 없음 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("처음 보는 비밀값이면 사용자와 기기 신원을 만들고, 원문이 아니라 해시만 저장한다")
    void anonymous_newSecret_createsUserWithHashedSubject() {
        OAuthLoginResult result = service.login(SECRET, null, IP);

        assertThat(storedUsers).hasSize(1);
        assertThat(storedUsers.get(0).getTossUserKey()).isNull();
        assertThat(storedIdentities).hasSize(1);
        AuthIdentity identity = storedIdentities.get(0);
        assertThat(identity.getProvider()).isEqualTo(AuthProvider.DEVICE);
        assertThat(identity.getProviderSubject())
                .isEqualTo(DeviceLoginService.hash(SECRET))
                .hasSize(64)
                .doesNotContain(SECRET);
        assertThat(result.userId()).isEqualTo(storedUsers.get(0).getId());
        assertThat(result.provider()).isEqualTo(AuthProvider.DEVICE);
        assertThat(result.accessToken()).isNotBlank();
        assertThat(result.refreshToken()).isNotBlank();
        verify(signupLimit).tryAcquire(IP);
    }

    @Test
    @DisplayName("같은 비밀값으로 다시 오면 같은 계정 — 새로 만들지 않고 가입 한도도 세지 않는다")
    void anonymous_knownSecret_reentersSameUser() {
        long first = service.login(SECRET, null, IP).userId();

        OAuthLoginResult second = service.login(SECRET, null, IP);

        assertThat(second.userId()).isEqualTo(first);
        assertThat(storedUsers).hasSize(1);
        assertThat(storedIdentities).hasSize(1);
        verify(signupLimit, times(1)).tryAcquire(anyString());
    }

    @Test
    @DisplayName("재진입하면 마지막 접속 시각을 갱신한다")
    void anonymous_reentry_touchesLastLogin() {
        service.login(SECRET, null, IP);
        AuthIdentity identity = storedIdentities.get(0);

        service.login(SECRET, null, IP);

        assertThat(identity.getLastLoginAt()).isEqualTo(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("가입 한도를 넘으면 거부하고 사용자를 만들지 않는다")
    void anonymous_rateLimited_rejectsWithoutCreating() {
        when(signupLimit.tryAcquire(anyString())).thenReturn(false);

        assertThatThrownBy(() -> service.login(SECRET, null, IP))
                .isInstanceOf(DeviceSignupRateLimitedException.class);

        verify(userRepository, never()).saveAndFlush(any(User.class));
        assertThat(storedIdentities).isEmpty();
    }

    @Test
    @DisplayName("가입 한도가 차 있어도 이미 있는 계정의 재진입은 막지 않는다")
    void anonymous_rateLimited_stillAllowsReentry() {
        long first = service.login(SECRET, null, IP).userId();
        when(signupLimit.tryAcquire(anyString())).thenReturn(false);

        assertThat(service.login(SECRET, null, IP).userId()).isEqualTo(first);
    }

    // ── Bearer 있음(연결) ────────────────────────────────────────────────────

    @Test
    @DisplayName("애플 사용자가 처음 보는 비밀값으로 오면 그 계정에 기기를 붙인다 — 새 사용자를 만들지 않고 한도도 세지 않는다")
    void bearer_newSecret_linksToCurrentAppleUser() {
        long appleUser = existingUserWith(AuthProvider.APPLE);

        OAuthLoginResult result = service.login(SECRET, appleUser, IP);

        assertThat(result.userId()).isEqualTo(appleUser);
        assertThat(storedUsers).hasSize(1);
        assertThat(storedIdentities).filteredOn(i -> i.getProvider() == AuthProvider.DEVICE)
                .singleElement()
                .satisfies(i -> assertThat(i.getUserId()).isEqualTo(appleUser));
        verify(signupLimit, never()).tryAcquire(anyString());
    }

    @Test
    @DisplayName("카카오 사용자도 연결할 수 있다")
    void bearer_newSecret_linksToKakaoUser() {
        long kakaoUser = existingUserWith(AuthProvider.KAKAO);

        assertThat(service.login(SECRET, kakaoUser, IP).userId()).isEqualTo(kakaoUser);
    }

    @Test
    @DisplayName("이미 그 계정에 붙은 비밀값이면 그대로 토큰을 준다")
    void bearer_sameUserSecret_issuesTokens() {
        long appleUser = existingUserWith(AuthProvider.APPLE);
        service.login(SECRET, appleUser, IP);

        assertThat(service.login(SECRET, appleUser, IP).userId()).isEqualTo(appleUser);
        assertThat(storedIdentities).filteredOn(i -> i.getProvider() == AuthProvider.DEVICE).hasSize(1);
    }

    @Test
    @DisplayName("다른 계정에 묶인 비밀값을 Bearer 로 가져오면 409 — 남의 기기를 내 계정으로 끌어오지 않는다")
    void bearer_otherUsersSecret_rejected() {
        long deviceUser = service.login(SECRET, null, IP).userId();
        long appleUser = existingUserWith(AuthProvider.APPLE);

        assertThatThrownBy(() -> service.login(SECRET, appleUser, IP))
                .isInstanceOf(DeviceAlreadyLinkedException.class);

        AuthIdentity device = storedIdentities.stream()
                .filter(i -> i.getProvider() == AuthProvider.DEVICE).findFirst().orElseThrow();
        assertThat(device.getUserId()).isEqualTo(deviceUser);
    }

    @Test
    @DisplayName("Bearer 없이 오면 다른 계정에 묶인 비밀값이라도 그 계정 토큰을 준다 — 비밀값이 곧 열쇠다")
    void anonymous_secretOfAppleUser_returnsThatUser() {
        long appleUser = existingUserWith(AuthProvider.APPLE);
        service.login(SECRET, appleUser, IP);

        assertThat(service.login(SECRET, null, IP).userId()).isEqualTo(appleUser);
    }

    @Test
    @DisplayName("토스 신원만 있는 계정에는 붙이지 않는다 — 미니앱 계정과 독립 앱 계정이 섞이지 않게")
    void bearer_tossOnlyUser_rejected() {
        long tossUser = existingUserWith(AuthProvider.TOSS);

        assertThatThrownBy(() -> service.login(SECRET, tossUser, IP))
                .isInstanceOf(DeviceLinkNotAllowedException.class);
        assertThat(storedIdentities).noneMatch(i -> i.getProvider() == AuthProvider.DEVICE);
    }

    @Test
    @DisplayName("기기만 있는 계정에 다른 비밀값을 붙이지 않는다 — 훔친 토큰으로 기기 계정에 열쇠를 더 만드는 길을 막는다")
    void bearer_deviceOnlyUser_rejected() {
        long deviceUser = service.login(SECRET, null, IP).userId();

        assertThatThrownBy(() -> service.login(OTHER_SECRET, deviceUser, IP))
                .isInstanceOf(DeviceLinkNotAllowedException.class);
    }

    @Test
    @DisplayName("기기는 계정당 3대까지 — 넷째는 거부한다")
    void bearer_fourthDevice_rejected() {
        long appleUser = existingUserWith(AuthProvider.APPLE);
        service.login("a".repeat(43), appleUser, IP);
        service.login("b".repeat(43), appleUser, IP);
        service.login("c".repeat(43), appleUser, IP);

        assertThatThrownBy(() -> service.login("d".repeat(43), appleUser, IP))
                .isInstanceOf(DeviceLinkLimitExceededException.class);
        assertThat(storedIdentities).filteredOn(i -> i.getProvider() == AuthProvider.DEVICE).hasSize(3);
    }

    // ── 형식 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("형식이 틀린 비밀값은 아무것도 조회·저장하지 않고 거부하며, 메시지에 원문을 담지 않는다")
    void invalidSecret_rejectedWithoutLeaking() {
        String tooShort = "x".repeat(42);
        String badChar = "a".repeat(42) + "=";
        String tooLong = "a".repeat(129);

        for (String bad : new String[]{null, "", tooShort, badChar, tooLong}) {
            assertThatThrownBy(() -> service.login(bad, null, IP))
                    .isInstanceOf(InvalidDeviceSecretException.class)
                    .satisfies(e -> {
                        if (bad != null && !bad.isEmpty()) {
                            assertThat(e.getMessage()).doesNotContain(bad);
                        }
                    });
        }
        verify(identityRepository, never()).findByProviderAndProviderSubjectAndDeletedAtIsNull(any(), any());
        verify(signupLimit, never()).tryAcquire(anyString());
    }

    // ── 경합 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 비밀값 동시 첫 호출로 유니크 위반이 나면 재조회해 상대가 만든 계정을 쓴다")
    void signupRace_resolvesToWinner() {
        // 상대 트랜잭션이 먼저 커밋한 상황: 우리 저장은 유니크 위반, 재조회하면 상대 행이 보인다.
        User winnerUser = User.builder().build();
        assignId(winnerUser, 777L);
        storedUsers.add(winnerUser);
        AuthIdentity winner = AuthIdentity.builder()
                .userId(777L).provider(AuthProvider.DEVICE)
                .providerSubject(DeviceLoginService.hash(SECRET))
                .linkedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
        AtomicBoolean firstSave = new AtomicBoolean(true);
        when(identityRepository.saveAndFlush(any(AuthIdentity.class))).thenAnswer(inv -> {
            if (firstSave.getAndSet(false)) {
                storedIdentities.add(winner);
                throw new DataIntegrityViolationException("uq_auth_identity_provider_subject");
            }
            throw new AssertionError("재조회 경로에서 다시 저장하면 안 된다");
        });

        OAuthLoginResult result = service.login(SECRET, null, IP);

        assertThat(result.userId()).isEqualTo(777L);
    }

    // ── 도우미 ───────────────────────────────────────────────────────────────

    private long existingUserWith(AuthProvider provider) {
        User user = User.builder().build();
        assignId(user, sequence.incrementAndGet());
        storedUsers.add(user);
        AuthIdentity identity = AuthIdentity.builder()
                .userId(user.getId()).provider(provider)
                .providerSubject(provider.name().toLowerCase() + "-" + user.getId())
                .linkedAt(LocalDateTime.ofInstant(NOW, ZoneOffset.UTC)).build();
        assignId(identity, sequence.incrementAndGet());
        storedIdentities.add(identity);
        return user.getId();
    }

    private static void assignId(Object entity, long id) {
        try {
            var field = entity.getClass().getSuperclass().getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("id 주입 실패", e);
        }
    }

    private static class NoopTransactionManager implements PlatformTransactionManager {
        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
