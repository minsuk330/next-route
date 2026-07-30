package watoo.grd.nextroute.application.auth.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.auth.dto.MeResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.exception.SessionException;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 로그아웃이 실제로 세션을 끝내는지, 그리고 끝난 세션이 되살아나지 않는지 고정한다.
 *
 * <p>세대 번호는 {@code users} 에 있고 토큰에 복사돼 나간다. 그래서 확인할 것이 둘이다.
 * 로그아웃이 번호를 올리는가, 그리고 재발급이 옛 번호를 거절하는가.
 */
@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

    @Mock UserRepository userRepository;
    @Mock AuthIdentityRepository identityRepository;
    @Mock JwtProvider jwtProvider;

    @InjectMocks SessionService service;

    private static User user(long id, int generation) {
        User u = User.builder().build();
        ReflectionTestUtils.setField(u, "id", id);
        ReflectionTestUtils.setField(u, "tokenGeneration", generation);
        return u;
    }

    private static AuthIdentity identity(AuthProvider provider, String name, String email,
                                         LocalDateTime linkedAt) {
        return AuthIdentity.builder()
                .userId(1L)
                .provider(provider)
                .providerSubject("subject-" + provider)
                .displayName(name)
                .email(email)
                .linkedAt(linkedAt)
                .build();
    }

    // ── 로그아웃 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("로그아웃하면 세대 번호가 올라간다 — 이 한 줄이 발급된 토큰을 전부 끊는다")
    void logout_bumpsGeneration() {
        User u = user(1L, 0);
        given(userRepository.findById(1L)).willReturn(Optional.of(u));

        service.logout(1L);

        assertThat(u.getTokenGeneration()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 사용자의 로그아웃은 거부한다")
    void logout_unknownUser_throws() {
        given(userRepository.findById(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.logout(9L)).isInstanceOf(SessionException.class);
    }

    // ── 재발급 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("세대가 맞으면 새 쌍을 내준다")
    void reissue_currentGeneration_returnsNewPair() {
        given(jwtProvider.parseRefreshPrincipal("ref"))
                .willReturn(new JwtProvider.TokenPrincipal(1L, 2));
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 2)));
        given(jwtProvider.createAccessToken(1L, 2)).willReturn("newAccess");
        given(jwtProvider.createRefreshToken(1L, 2)).willReturn("newRefresh");

        TokenResult result = service.reissue("ref");

        assertThat(result.accessToken()).isEqualTo("newAccess");
        assertThat(result.refreshToken()).isEqualTo("newRefresh");
    }

    @Test
    @DisplayName("로그아웃 뒤의 refresh 는 거부한다 — 통과시키면 로그아웃이 1시간짜리로 전락한다")
    void reissue_staleGeneration_throws() {
        given(jwtProvider.parseRefreshPrincipal("old"))
                .willReturn(new JwtProvider.TokenPrincipal(1L, 0));
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 1)));

        assertThatThrownBy(() -> service.reissue("old")).isInstanceOf(SessionException.class);
    }

    @Test
    @DisplayName("해석 자체가 실패하면 거부한다 — 만료·위조·access 를 refresh 로 보낸 경우")
    void reissue_invalidToken_throws() {
        given(jwtProvider.parseRefreshPrincipal("bad")).willThrow(new RuntimeException("bad"));

        assertThatThrownBy(() -> service.reissue("bad")).isInstanceOf(SessionException.class);
    }

    @Test
    @DisplayName("사라진 사용자의 refresh 는 서명이 멀쩡해도 거부한다")
    void reissue_unknownUser_throws() {
        given(jwtProvider.parseRefreshPrincipal("ref"))
                .willReturn(new JwtProvider.TokenPrincipal(1L, 0));
        given(userRepository.findById(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.reissue("ref")).isInstanceOf(SessionException.class);
    }

    // ── 내 정보 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("연결된 로그인 수단을 연결 순서대로 돌려준다")
    void me_returnsIdentities() {
        LocalDateTime older = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime newer = LocalDateTime.of(2026, 6, 1, 0, 0);
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 0)));
        given(identityRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(
                identity(AuthProvider.KAKAO, "길동", "user@kakao.com", newer),
                identity(AuthProvider.TOSS, "토스이름", "toss@example.com", older)));

        MeResult result = service.me(1L);

        assertThat(result.userId()).isEqualTo(1L);
        assertThat(result.identities()).extracting(MeResult.LinkedIdentity::provider)
                .containsExactly(AuthProvider.TOSS, AuthProvider.KAKAO);
    }

    @Test
    @DisplayName("표시 이름은 앱 수단에서 먼저 찾는다 — 토스 값은 미니앱 쪽 것이다")
    void me_prefersAppProviderProfile() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 0)));
        given(identityRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(
                identity(AuthProvider.TOSS, "토스이름", "toss@example.com", LocalDateTime.now()),
                identity(AuthProvider.KAKAO, "길동", "user@kakao.com", LocalDateTime.now())));

        MeResult result = service.me(1L);

        assertThat(result.displayName()).isEqualTo("길동");
        assertThat(result.email()).isEqualTo("user@kakao.com");
    }

    @Test
    @DisplayName("앱 수단이 이름을 안 채웠으면 토스 값이라도 쓴다")
    void me_fallsBackToAnyIdentity() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 0)));
        given(identityRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(
                identity(AuthProvider.TOSS, "토스이름", "toss@example.com", LocalDateTime.now()),
                identity(AuthProvider.KAKAO, null, null, LocalDateTime.now())));

        MeResult result = service.me(1L);

        assertThat(result.displayName()).isEqualTo("토스이름");
    }

    @Test
    @DisplayName("아무도 이름을 안 줬으면 비워 둔다 — 애플은 이름을 안 줄 수 있다")
    void me_noProfileAnywhere_returnsNulls() {
        given(userRepository.findById(1L)).willReturn(Optional.of(user(1L, 0)));
        given(identityRepository.findByUserIdAndDeletedAtIsNull(1L)).willReturn(List.of(
                identity(AuthProvider.APPLE, null, null, LocalDateTime.now())));

        MeResult result = service.me(1L);

        assertThat(result.displayName()).isNull();
        assertThat(result.email()).isNull();
        assertThat(result.identities()).hasSize(1);
    }

    @Test
    @DisplayName("없는 사용자는 거부한다")
    void me_unknownUser_throws() {
        given(userRepository.findById(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.me(9L)).isInstanceOf(SessionException.class);
    }
}
