package watoo.grd.nextroute.application.auth.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {

    /** HMAC-SHA256 이 요구하는 최소 키 길이(바이트). 256bit. */
    private static final int MIN_SECRET_BYTES = 32;

    /** HMAC-SHA 서명 시크릿. env/auth.env 의 AUTH_JWT_SECRET 로 주입한다. */
    private String secret;

    private String issuer = "nextroute";

    /** access 토큰 유효시간(초). 기본 1시간. */
    private long accessTtlSeconds = 3600;

    /** refresh 토큰 유효시간(초). 기본 14일. */
    private long refreshTtlSeconds = 60L * 60 * 24 * 14;

    @PostConstruct
    void validate() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "auth.jwt.secret 이 비어 있다. env/auth.env 에 AUTH_JWT_SECRET 을 채워라. "
                            + "예시는 env/auth.env.example 참고.");
        }
        int bytes = secret.getBytes(StandardCharsets.UTF_8).length;
        if (bytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "auth.jwt.secret 이 너무 짧다(" + bytes + " bytes). HMAC-SHA256 은 최소 "
                            + MIN_SECRET_BYTES + " bytes 를 요구한다. "
                            + "python3 -c \"import secrets; print(secrets.token_urlsafe(48))\" 로 생성해라.");
        }
    }
}
