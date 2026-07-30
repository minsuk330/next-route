package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.port.out.OAuthChallengePort;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * 애플 일회용 값 저장소. 키는 {@code oauth:challenge:{nonce}}, 값은 자리표시자다.
 *
 * <p>확인과 삭제를 한 번에 한다({@code getAndDelete}). 두 요청이 같은 값으로 동시에 들어와도
 * Redis 가 단일 스레드로 처리하므로 하나만 성공한다. 확인 후 따로 지우면 그 사이에 재사용이 통과한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisOAuthChallengeAdapter implements OAuthChallengePort {

    private static final String KEY_PREFIX = "oauth:challenge:";
    private static final int NONCE_BYTES = 32;

    private final RedisTemplate<String, String> redisTemplate;
    private final OAuthProviderProperties properties;
    private final SecureRandom random = new SecureRandom();

    @Override
    public String issue() {
        byte[] bytes = new byte[NONCE_BYTES];
        random.nextBytes(bytes);
        String nonce = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        redisTemplate.opsForValue().set(
                KEY_PREFIX + nonce, "1",
                Duration.ofSeconds(properties.getChallenge().getTtlSeconds()));
        return nonce;
    }

    @Override
    public boolean consume(String nonce) {
        if (nonce == null || nonce.isBlank()) {
            return false;
        }
        try {
            return redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + nonce) != null;
        } catch (Exception e) {
            // 저장소 장애 시 통과시키면 재전송 방어가 사라진다. 막는 쪽으로 실패한다.
            log.warn("[OAuthChallenge] 조회 실패 — 로그인을 거부한다: {}", e.toString());
            return false;
        }
    }
}
