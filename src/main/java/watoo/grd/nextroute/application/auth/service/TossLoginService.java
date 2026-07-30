package watoo.grd.nextroute.application.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.auth.dto.LoginResult;
import watoo.grd.nextroute.application.auth.dto.TokenResult;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.in.TossLoginUseCase;
import watoo.grd.nextroute.application.auth.port.out.TossLoginPort;
import watoo.grd.nextroute.common.security.JwtProvider;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;
import watoo.grd.nextroute.domain.auth.entity.TossUserToken;
import watoo.grd.nextroute.domain.auth.repository.AuthIdentityRepository;
import watoo.grd.nextroute.domain.auth.repository.TossUserTokenRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class TossLoginService implements TossLoginUseCase {

    /** refresh 토큰 유효기간(일). 토스 정책 14일. */
    private static final long REFRESH_TTL_DAYS = 14;

    private final TossLoginPort tossLoginPort;
    private final UserRepository userRepository;
    private final AuthIdentityRepository authIdentityRepository;
    private final TossUserTokenRepository tossUserTokenRepository;
    private final JwtProvider jwtProvider;

    @Override
    public LoginResult login(String authorizationCode, String referrer) {
        TossLoginPort.TossToken token = tossLoginPort.generateToken(authorizationCode, referrer);
        TossLoginPort.TossUserInfo info = tossLoginPort.getUserInfo(token.accessToken());

        User user = upsertUser(info.userKey());
        storeTossToken(info.userKey(), token);

        String accessJwt = jwtProvider.createAccessToken(user.getId(), user.getTokenGeneration());
        String refreshJwt = jwtProvider.createRefreshToken(user.getId(), user.getTokenGeneration());

        return new LoginResult(accessJwt, refreshJwt, user.getId(), info.userKey(), info.name());
    }

    @Override
    public TokenResult reissue(String refreshToken) {
        JwtProvider.TokenPrincipal principal;
        try {
            principal = jwtProvider.parseRefreshPrincipal(refreshToken);
        } catch (RuntimeException e) {
            throw new TossLoginException("유효하지 않은 refresh 토큰", false, e);
        }
        long userId = principal.userId();
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TossLoginException("사용자 없음: " + userId, false));
        // 미니앱에는 세대를 올리는 경로가 없어 실질적으로 항상 같다. 규칙에 예외를 두지 않으려고 검사한다.
        if (user.getTokenGeneration() != principal.generation()) {
            throw new TossLoginException("이미 폐기된 세션이다", false);
        }
        return new TokenResult(
                jwtProvider.createAccessToken(userId, user.getTokenGeneration()),
                jwtProvider.createRefreshToken(userId, user.getTokenGeneration())
        );
    }

    @Override
    public void logout(long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new TossLoginException("사용자 없음: " + userId, false));
        Long tossUserKey = user.getTossUserKey();
        if (tossUserKey == null) {
            return;
        }
        tossUserTokenRepository.findByUserKey(tossUserKey).ifPresent(t -> {
            try {
                tossLoginPort.unlinkByAccessToken(t.getAccessToken());
            } catch (TossLoginException e) {
                log.warn("[TOSS] unlink 실패(토큰 만료 가능) userKey={}: {}", tossUserKey, e.getMessage());
            }
            tossUserTokenRepository.deleteByUserKey(tossUserKey);
        });
    }

    @Override
    public void handleUnlinkCallback(long userKey, String referrer) {
        log.info("[TOSS] unlink 콜백 userKey={} referrer={}", userKey, referrer);
        tossUserTokenRepository.deleteByUserKey(userKey);
    }

    // ---- helpers ----

    /**
     * 토스 신원으로 사용자를 찾거나 만든다.
     *
     * <p>V33 이후 신원 조회의 기준은 {@code auth_identity} 다. {@code users.toss_user_key} 는
     * 연결끊기 콜백 경로가 아직 쓰는 레거시 미러라 계속 채우되, 찾을 때는 보지 않는다.
     *
     * <p>지운 표시가 된 신분증은 조회에서 빠진다. 이 저장소는 자동 필터 장치를 쓰지 않으므로
     * 메서드 이름에 조건이 들어가 있다.
     */
    private User upsertUser(long tossUserKey) {
        String subject = String.valueOf(tossUserKey);
        return authIdentityRepository
                .findByProviderAndProviderSubjectAndDeletedAtIsNull(AuthProvider.TOSS, subject)
                .flatMap(identity -> userRepository.findById(identity.getUserId()))
                .orElseGet(() -> createUserWithTossIdentity(tossUserKey, subject));
    }

    private User createUserWithTossIdentity(long tossUserKey, String subject) {
        User user = userRepository.saveAndFlush(User.builder().tossUserKey(tossUserKey).build());
        authIdentityRepository.save(AuthIdentity.builder()
                .userId(user.getId())
                .provider(AuthProvider.TOSS)
                .providerSubject(subject)
                .linkedAt(LocalDateTime.now())
                .build());
        return user;
    }

    private void storeTossToken(long userKey, TossLoginPort.TossToken token) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime accessExp = now.plusSeconds(token.expiresIn());
        LocalDateTime refreshExp = now.plusDays(REFRESH_TTL_DAYS);
        tossUserTokenRepository.findByUserKey(userKey)
                .ifPresentOrElse(
                        t -> t.update(token.accessToken(), token.refreshToken(), accessExp, refreshExp),
                        () -> tossUserTokenRepository.save(TossUserToken.builder()
                                .userKey(userKey)
                                .accessToken(token.accessToken())
                                .refreshToken(token.refreshToken())
                                .accessExpiresAt(accessExp)
                                .refreshExpiresAt(refreshExp)
                                .build())
                );
    }
}
