package watoo.grd.nextroute.application.auth.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.auth.dto.LoginResult;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.out.TossLoginPort;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.TossUserToken;
import watoo.grd.nextroute.domain.auth.repository.TossUserTokenRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class TossLoginServiceTest {

    @Mock TossLoginPort tossLoginPort;
    @Mock UserRepository userRepository;
    @Mock AuthIdentityRepository authIdentityRepository;
    @Mock TossUserTokenRepository tossUserTokenRepository;
    @Mock JwtProvider jwtProvider;

    @InjectMocks TossLoginService service;

    private User user(long id, Long tossUserKey) {
        User u = User.builder().tossUserKey(tossUserKey).build();
        ReflectionTestUtils.setField(u, "id", id);
        return u;
    }

    @BeforeEach
    void setUp() { }

    @Test
    void login_newUser_issuesJwt_andStoresToken() {
        given(tossLoginPort.generateToken("code", "DEFAULT"))
                .willReturn(new TossLoginPort.TossToken("Bearer", "tAcc", "tRef", 3599, "user_name"));
        given(tossLoginPort.getUserInfo("tAcc"))
                .willReturn(new TossLoginPort.TossUserInfo(999L, "user_name", List.of("t1"),
                        "홍길동", null, null, null, null, null, null));
        // V33 이후 신원 조회의 기준은 auth_identity 다. 신분증이 없으면 새 사용자를 만든다.
        given(authIdentityRepository.findByProviderAndProviderSubjectAndDeletedAtIsNull(
                AuthProvider.TOSS, "999")).willReturn(Optional.empty());
        given(userRepository.saveAndFlush(any(User.class))).willReturn(user(100L, 999L));
        given(tossUserTokenRepository.findByUserKey(999L)).willReturn(Optional.empty());
        given(jwtProvider.createAccessToken(100L, 0)).willReturn("ourAcc");
        given(jwtProvider.createRefreshToken(100L, 0)).willReturn("ourRef");

        LoginResult result = service.login("code", "DEFAULT");

        assertThat(result.accessToken()).isEqualTo("ourAcc");
        assertThat(result.refreshToken()).isEqualTo("ourRef");
        assertThat(result.userId()).isEqualTo(100L);
        assertThat(result.tossUserKey()).isEqualTo(999L);
        assertThat(result.name()).isEqualTo("홍길동");

        ArgumentCaptor<TossUserToken> captor = ArgumentCaptor.forClass(TossUserToken.class);
        verify(tossUserTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getUserKey()).isEqualTo(999L);
        assertThat(captor.getValue().getAccessToken()).isEqualTo("tAcc");
    }

    @Test
    void login_existingTossUser_reused_notCreated() {
        User existing = user(50L, 999L);
        given(tossLoginPort.generateToken("code", "DEFAULT"))
                .willReturn(new TossLoginPort.TossToken("Bearer", "tAcc", "tRef", 3599, "user_name"));
        given(tossLoginPort.getUserInfo("tAcc"))
                .willReturn(new TossLoginPort.TossUserInfo(999L, "user_name", List.of(),
                        null, null, null, null, null, null, null));
        // 이관된 신분증으로 사용자를 찾는다. toss_user_key 칸은 더 이상 조회에 쓰지 않는다.
        given(authIdentityRepository.findByProviderAndProviderSubjectAndDeletedAtIsNull(
                AuthProvider.TOSS, "999"))
                .willReturn(Optional.of(AuthIdentity.builder()
                        .userId(50L)
                        .provider(AuthProvider.TOSS)
                        .providerSubject("999")
                        .linkedAt(java.time.LocalDateTime.now())
                        .build()));
        given(userRepository.findById(50L)).willReturn(Optional.of(existing));
        given(tossUserTokenRepository.findByUserKey(999L)).willReturn(Optional.empty());
        given(jwtProvider.createAccessToken(50L, 0)).willReturn("a");
        given(jwtProvider.createRefreshToken(50L, 0)).willReturn("r");

        LoginResult result = service.login("code", "DEFAULT");

        assertThat(result.userId()).isEqualTo(50L);
        verify(userRepository, org.mockito.Mockito.never()).saveAndFlush(any());
    }

    @Test
    void reissue_validRefresh_returnsNewPair() {
        given(jwtProvider.parseRefreshPrincipal("ref"))
                .willReturn(new JwtProvider.TokenPrincipal(7L, 0));
        given(userRepository.findById(7L)).willReturn(Optional.of(user(7L, 1L)));
        given(jwtProvider.createAccessToken(7L, 0)).willReturn("a2");
        given(jwtProvider.createRefreshToken(7L, 0)).willReturn("r2");

        var result = service.reissue("ref");

        assertThat(result.accessToken()).isEqualTo("a2");
        assertThat(result.refreshToken()).isEqualTo("r2");
    }

    @Test
    void reissue_invalidRefresh_throws() {
        given(jwtProvider.parseRefreshPrincipal("bad")).willThrow(new RuntimeException("bad"));
        assertThatThrownBy(() -> service.reissue("bad"))
                .isInstanceOf(TossLoginException.class);
    }

    @Test
    @DisplayName("폐기된 세대의 refresh 는 거부한다 — 미니앱에도 규칙의 예외를 두지 않는다")
    void reissue_staleGeneration_throws() {
        User u = user(7L, 1L);
        ReflectionTestUtils.setField(u, "tokenGeneration", 2);
        given(jwtProvider.parseRefreshPrincipal("old"))
                .willReturn(new JwtProvider.TokenPrincipal(7L, 1));
        given(userRepository.findById(7L)).willReturn(Optional.of(u));

        assertThatThrownBy(() -> service.reissue("old"))
                .isInstanceOf(TossLoginException.class);
    }

    @Test
    void logout_unlinksToss_andDeletesToken() {
        User u = user(10L, 999L);
        given(userRepository.findById(10L)).willReturn(Optional.of(u));
        given(tossUserTokenRepository.findByUserKey(999L))
                .willReturn(Optional.of(TossUserToken.builder()
                        .userKey(999L).accessToken("tAcc").refreshToken("tRef").build()));

        service.logout(10L);

        verify(tossLoginPort).unlinkByAccessToken("tAcc");
        verify(tossUserTokenRepository).deleteByUserKey(999L);
    }

    @Test
    void handleUnlinkCallback_deletesToken() {
        service.handleUnlinkCallback(999L, "UNLINK");

        verify(tossUserTokenRepository).deleteByUserKey(999L);
    }
}
