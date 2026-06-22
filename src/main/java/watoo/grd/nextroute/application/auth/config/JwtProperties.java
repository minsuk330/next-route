package watoo.grd.nextroute.application.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.jwt")
public class JwtProperties {

    /** HMAC-SHA 서명 시크릿. 최소 256bit(32자) 이상. */
    private String secret = "change-me-this-is-a-dev-only-jwt-secret-please-override";

    private String issuer = "nextroute";

    /** access 토큰 유효시간(초). 기본 1시간. */
    private long accessTtlSeconds = 3600;

    /** refresh 토큰 유효시간(초). 기본 14일. */
    private long refreshTtlSeconds = 60L * 60 * 24 * 14;
}
