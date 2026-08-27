package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class AppleClientSecretJwtBuilderTest {

    @Test
    void build_signsWithP8_andCarriesAppleClaims() throws Exception {
        // P-256 EC 키쌍 생성 → 개인키를 .p8(PKCS#8 PEM) 로 임시 파일에 쓴다.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
        gen.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = gen.generateKeyPair();

        String pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(pair.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
        Path p8 = Files.createTempFile("apple-test", ".p8");
        Files.writeString(p8, pem);
        p8.toFile().deleteOnExit();

        OAuthProviderProperties props = new OAuthProviderProperties();
        OAuthProviderProperties.Apple apple = props.getApple();
        apple.setTeamId("TEAM123456");
        apple.setClientId("com.golbob.nextroute");
        apple.setKeyId("KEY7890123");
        apple.setPrivateKeyPath(p8.toString());

        String jwt = new AppleClientSecretJwtBuilder(props).build();

        // 공개키로 서명·클레임을 검증한다.
        Jws<Claims> parsed = Jwts.parser()
                .verifyWith((ECPublicKey) pair.getPublic())
                .build()
                .parseSignedClaims(jwt);

        assertThat(parsed.getHeader().getKeyId()).isEqualTo("KEY7890123");
        assertThat(parsed.getHeader().getAlgorithm()).isEqualTo("ES256");
        Claims c = parsed.getPayload();
        assertThat(c.getIssuer()).isEqualTo("TEAM123456");
        assertThat(c.getSubject()).isEqualTo("com.golbob.nextroute");
        assertThat(c.getAudience()).contains("https://appleid.apple.com");
        assertThat(c.getExpiration()).isAfter(c.getIssuedAt());
    }
}
