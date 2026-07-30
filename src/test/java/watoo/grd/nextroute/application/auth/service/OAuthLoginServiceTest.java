package watoo.grd.nextroute.application.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import watoo.grd.nextroute.application.auth.config.JwtProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.dto.OAuthLoginResult;
import watoo.grd.nextroute.application.auth.dto.VerifiedIdentity;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.exception.ProviderNotAllowedException;
import watoo.grd.nextroute.application.auth.port.out.OAuthChallengePort;
import watoo.grd.nextroute.application.auth.port.out.OAuthIdentityVerifier;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 로그인 정책을 고정한다.
 *
 * <p>가장 중요한 것은 <b>계정을 언제 새로 만드는가</b>다. 이메일이 같다고 합치면 남의 이메일을
 * 아는 사람이 계정에 들어간다.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OAuthLoginServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T10:00:00Z");

    @Mock AuthIdentityRepository identityRepository;
    @Mock UserRepository userRepository;
    @Mock OAuthChallengePort challengePort;

    private StubVerifier appleVerifier;
    private StubVerifier kakaoVerifier;
    private OAuthLoginService service;

    /** 저장된 것을 흉내 내는 인메모리 저장소. */
    private final List<AuthIdentity> storedIdentities = new ArrayList<>();
    private final List<User> storedUsers = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    @BeforeEach
    void setUp() {
        appleVerifier = new StubVerifier(AuthProvider.APPLE);
        kakaoVerifier = new StubVerifier(AuthProvider.KAKAO);

        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User user = inv.getArgument(0);
            assignId(user, sequence.incrementAndGet());
            storedUsers.add(user);
            return user;
        });
        // 토큰에 담을 세대 번호를 읽으려고 발급 직전에 사용자를 다시 본다.
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

        JwtProperties jwtProps = new JwtProperties();
        jwtProps.setSecret("test-secret-test-secret-test-secret-0123456789");
        jwtProps.setIssuer("nextroute-test");

        service = new OAuthLoginService(
                List.of(appleVerifier, kakaoVerifier),
                identityRepository, userRepository, challengePort,
                new JwtProvider(jwtProps),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new NoopTransactionManager());
    }

    // ── 가입·재로그인 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("처음 보는 신원이면 사용자와 신분증을 함께 만든다")
    void firstLogin_createsUserAndIdentity() {
        appleVerifier.willReturn("apple-1", "a@example.com");

        OAuthLoginResult result = login(AuthProvider.APPLE, "홍길동");

        assertThat(result.userId()).isPositive();
        assertThat(result.provider()).isEqualTo(AuthProvider.APPLE);
        assertThat(result.accessToken()).isNotBlank();
        assertThat(storedUsers).hasSize(1);
        assertThat(storedIdentities).hasSize(1);
        assertThat(storedIdentities.get(0).getProviderSubject()).isEqualTo("apple-1");
        assertThat(storedIdentities.get(0).getDisplayName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("새 사용자는 토스 번호 없이 만들어진다")
    void newUser_hasNoTossKey() {
        appleVerifier.willReturn("apple-1", null);

        login(AuthProvider.APPLE, null);

        assertThat(storedUsers.get(0).getTossUserKey()).isNull();
        assertThat(storedUsers.get(0).hasTossIdentity()).isFalse();
    }

    @Test
    @DisplayName("같은 신원으로 다시 로그인하면 사용자를 새로 만들지 않는다")
    void repeatLogin_reusesUser() {
        appleVerifier.willReturn("apple-1", "a@example.com");
        long firstUserId = login(AuthProvider.APPLE, "홍길동").userId();

        OAuthLoginResult second = login(AuthProvider.APPLE, null);

        assertThat(second.userId()).isEqualTo(firstUserId);
        assertThat(storedUsers).hasSize(1);
        assertThat(storedIdentities).hasSize(1);
    }

    @Test
    @DisplayName("이메일이 같아도 다른 회사면 별개 계정이다 — 자동 병합은 계정 탈취 통로가 된다")
    void sameEmailDifferentProvider_createsSeparateAccounts() {
        appleVerifier.willReturn("apple-1", "same@example.com");
        kakaoVerifier.willReturn("kakao-1", "same@example.com");

        long appleUser = login(AuthProvider.APPLE, null).userId();
        long kakaoUser = login(AuthProvider.KAKAO, null).userId();

        assertThat(appleUser).isNotEqualTo(kakaoUser);
        assertThat(storedUsers).hasSize(2);
    }

    @Test
    @DisplayName("두 번째 로그인의 이름이 비어도 처음 받아 둔 값을 지우지 않는다")
    void laterLoginWithoutName_keepsStoredName() {
        appleVerifier.willReturn("apple-1", "a@example.com");
        login(AuthProvider.APPLE, "홍길동");

        login(AuthProvider.APPLE, null);

        assertThat(storedIdentities.get(0).getDisplayName()).isEqualTo("홍길동");
    }

    @Test
    @DisplayName("재로그인 시각을 갱신한다")
    void repeatLogin_touchesLastLoginAt() {
        appleVerifier.willReturn("apple-1", null);
        login(AuthProvider.APPLE, null);

        login(AuthProvider.APPLE, null);

        assertThat(storedIdentities.get(0).getLastLoginAt())
                .isEqualTo(java.time.LocalDateTime.ofInstant(NOW, ZoneOffset.UTC));
    }

    // ── 거부 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("토스로는 앱에서 로그인할 수 없다 — 미니앱 전용이라 반드시 실패할 선택지다")
    void tossProvider_rejected() {
        assertThatThrownBy(() -> login(AuthProvider.TOSS, null))
                .isInstanceOf(ProviderNotAllowedException.class);

        assertThat(storedUsers).isEmpty();
        verify(userRepository, never()).saveAndFlush(any(User.class));
    }

    @Test
    @DisplayName("provider 가 없으면 거부한다")
    void nullProvider_rejected() {
        assertThatThrownBy(() -> login(null, null))
                .isInstanceOf(ProviderNotAllowedException.class);
    }

    @Test
    @DisplayName("사용자 번호가 비면 거부한다 — 빈 값이 통과하면 서로 다른 사람이 한 계정으로 합쳐진다")
    void blankSubject_rejected() {
        appleVerifier.willReturn("   ", "a@example.com");
        assertThatThrownBy(() -> login(AuthProvider.APPLE, null))
                .isInstanceOf(OAuthVerificationException.class);

        appleVerifier.willReturn("0", "a@example.com");
        assertThatThrownBy(() -> login(AuthProvider.APPLE, null))
                .isInstanceOf(OAuthVerificationException.class);

        appleVerifier.willReturn(null, "a@example.com");
        assertThatThrownBy(() -> login(AuthProvider.APPLE, null))
                .isInstanceOf(OAuthVerificationException.class);

        assertThat(storedUsers).isEmpty();
    }

    // ── challenge ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("challenge 발급은 저장소에 위임한다")
    void issueChallenge_delegates() {
        when(challengePort.issue()).thenReturn("server-nonce");

        assertThat(service.issueChallenge()).isEqualTo("server-nonce");
        verify(challengePort).issue();
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────────

    private OAuthLoginResult login(AuthProvider provider, String displayName) {
        return service.login(new OAuthCredential(provider, "token", "nonce", displayName));
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

    /** 검증 결과를 시험이 정하는 가짜 검증기. 실제 토큰 검증은 verifier 테스트가 따로 본다. */
    private static class StubVerifier implements OAuthIdentityVerifier {
        private final AuthProvider provider;
        private String subject;
        private String email;

        StubVerifier(AuthProvider provider) {
            this.provider = provider;
        }

        void willReturn(String subject, String email) {
            this.subject = subject;
            this.email = email;
        }

        @Override
        public AuthProvider provider() {
            return provider;
        }

        @Override
        public VerifiedIdentity verify(OAuthCredential credential) {
            return new VerifiedIdentity(provider, subject, email, credential.displayName());
        }
    }

    /** 트랜잭션 경계를 흉내만 낸다. 정책 검증에 실제 트랜잭션은 필요 없다. */
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
