package watoo.grd.nextroute.application.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "toss.login")
public class TossLoginProperties {

    /** 토스 로그인 API BaseURL. */
    private String baseUrl = "https://apps-in-toss-api.toss.im";

    /** mTLS 클라이언트 인증서 keystore(PKCS12) 경로. 비어 있으면 mTLS 미설정으로 간주. */
    private String keyStorePath;
    private String keyStorePassword;

    /** 사설 truststore(옵션). 비어 있으면 JVM 기본 truststore 사용. */
    private String trustStorePath;
    private String trustStorePassword;

    /** 사용자정보 PII 복호화 키(Base64). 콘솔→이메일 수신. */
    private String decryptKey;

    /** 복호화 AAD. 콘솔→이메일 수신(보통 "TOSS"). */
    private String aad = "TOSS";

    /** 연결끊기 콜백 Basic Auth 자격증명. */
    private String callbackUsername;
    private String callbackPassword;

    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 5000;
    private int maxRetries = 3;
}
