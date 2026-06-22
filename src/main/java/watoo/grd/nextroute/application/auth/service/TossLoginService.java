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
import watoo.grd.nextroute.domain.auth.entity.TossUserToken;
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
    private final TossUserTokenRepository tossUserTokenRepository;
    private final JwtProvider jwtProvider;

    @Override
    public LoginResult login(String authorizationCode, String referrer) {
        TossLoginPort.TossToken token = tossLoginPort.generateToken(authorizationCode, referrer);
        TossLoginPort.TossUserInfo info = tossLoginPort.getUserInfo(token.accessToken());

        User user = upsertUser(info.userKey());
        storeTossToken(info.userKey(), token);

        String accessJwt = jwtProvider.createAccessToken(user.getId());
        String refreshJwt = jwtProvider.createRefreshToken(user.getId());

        return new LoginResult(accessJwt, refreshJwt, user.getId(), info.userKey(), info.name());
    }

    @Override
    public TokenResult reissue(String refreshToken) {
        long userId;
        try {
            userId = jwtProvider.parseRefreshUserId(refreshToken);
        } catch (RuntimeException e) {
            throw new TossLoginException("유효하지 않은 refresh 토큰", false, e);
        }
        userRepository.findById(userId)
                .orElseThrow(() -> new TossLoginException("사용자 없음: " + userId, false));
        return new TokenResult(
                jwtProvider.createAccessToken(userId),
                jwtProvider.createRefreshToken(userId)
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

    private User upsertUser(long tossUserKey) {
        return userRepository.findByTossUserKey(tossUserKey)
                .orElseGet(() -> userRepository.saveAndFlush(
                        User.builder().tossUserKey(tossUserKey).build()));
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
