package watoo.grd.nextroute.infrastructure.adapter.out.api.toss;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 토스 사용자정보 PII 복호화. AES-256-GCM, IV=암호문 앞 12바이트, tag 128bit, AAD 적용.
 * 알고리즘은 토스 로그인 개발 문서 JAVA 예제 기반.
 */
@Component
@RequiredArgsConstructor
public class TossCryptoService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final TossLoginProperties properties;

    /** 암호화된 Base64 문자열을 평문으로 복호화. null/blank는 그대로 반환. */
    public String decrypt(String encryptedText) {
        if (encryptedText == null || encryptedText.isBlank()) {
            return encryptedText;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptedText);
            byte[] keyBytes = Base64.getDecoder().decode(properties.getDecryptKey());

            SecretKeySpec key = new SecretKeySpec(keyBytes, "AES");
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(decoded, 0, iv, 0, IV_LENGTH);
            GCMParameterSpec spec = new GCMParameterSpec(TAG_BITS, iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, spec);
            cipher.updateAAD(properties.getAad().getBytes(StandardCharsets.UTF_8));

            byte[] decrypted = cipher.doFinal(decoded, IV_LENGTH, decoded.length - IV_LENGTH);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("토스 PII 복호화 실패: " + e.getMessage(), e);
        }
    }
}
