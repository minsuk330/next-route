package watoo.grd.nextroute.application.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.auth.dto.MeResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.exception.SessionException;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * 로그아웃·재발급·내 정보.
 *
 * <p>로그아웃이 실제로 무언가를 끝내게 하는 것이 이 클래스의 핵심이다. JWT 는 한 번 내보내면
 * 만료 전까지 되돌릴 수 없으므로, 사용자마다 둔 세대 번호를 올려서 이전 토큰을 한꺼번에 끊는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService implements SessionUseCase {

    private final UserRepository userRepository;
    private final AuthIdentityRepository identityRepository;
    private final JwtProvider jwtProvider;

    @Override
    @Transactional(readOnly = true)
    public TokenResult reissue(String refreshToken) {
        JwtProvider.TokenPrincipal principal;
        try {
            principal = jwtProvider.parseRefreshPrincipal(refreshToken);
        } catch (RuntimeException e) {
            throw new SessionException("유효하지 않은 refresh 토큰", e);
        }

        User user = requireUser(principal.userId());
        if (user.getTokenGeneration() != principal.generation()) {
            // 로그아웃 뒤의 refresh 다. 여기서 막지 않으면 로그아웃이 access 수명만큼만 유효해진다.
            log.debug("[Session] 폐기된 세대의 refresh: userId={}", principal.userId());
            throw new SessionException("이미 로그아웃된 세션이다");
        }

        return new TokenResult(
                jwtProvider.createAccessToken(user.getId(), user.getTokenGeneration()),
                jwtProvider.createRefreshToken(user.getId(), user.getTokenGeneration()));
    }

    /**
     * 세대 번호를 올린다. 이 시점 이전에 발급된 access·refresh 가 전부 무효가 된다.
     *
     * <p>토스 연결 끊기는 하지 않는다. 로그아웃은 세션 종료이지 계정 해제가 아니다.
     */
    @Override
    @Transactional
    public void logout(long userId) {
        requireUser(userId).bumpTokenGeneration();
    }

    @Override
    @Transactional(readOnly = true)
    public MeResult me(long userId) {
        User user = requireUser(userId);
        List<AuthIdentity> identities = identityRepository.findByUserIdAndDeletedAtIsNull(userId);

        return new MeResult(
                user.getId(),
                displayNameOf(identities),
                emailOf(identities),
                identities.stream()
                        .sorted(Comparator.comparing(AuthIdentity::getLinkedAt))
                        .map(i -> new MeResult.LinkedIdentity(i.getProvider(), i.getLinkedAt()))
                        .toList());
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new SessionException("사용자 없음: " + userId));
    }

    /**
     * 표시용 값은 앱 로그인 수단(애플·카카오)에서 먼저 찾는다.
     *
     * <p>토스 신원은 미니앱 쪽 값이라 앱 화면에 보여 줄 이름으로는 뒤로 미룬다. 앱 수단이
     * 값을 안 채웠으면 그때 토스 것이라도 쓴다.
     */
    private static String displayNameOf(List<AuthIdentity> identities) {
        return preferAppProvider(identities, AuthIdentity::getDisplayName);
    }

    private static String emailOf(List<AuthIdentity> identities) {
        return preferAppProvider(identities, AuthIdentity::getEmail);
    }

    private static String preferAppProvider(List<AuthIdentity> identities,
                                            Function<AuthIdentity, String> field) {
        return firstNonBlank(identities.stream().filter(i -> i.getProvider() != AuthProvider.TOSS), field)
                .or(() -> firstNonBlank(identities.stream(), field))
                .orElse(null);
    }

    private static Optional<String> firstNonBlank(Stream<AuthIdentity> identities,
                                                  Function<AuthIdentity, String> field) {
        return identities.map(field)
                .filter(v -> v != null && !v.isBlank())
                .findFirst();
    }
}
