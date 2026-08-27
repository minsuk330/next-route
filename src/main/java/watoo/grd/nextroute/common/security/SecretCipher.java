package watoo.grd.nextroute.common.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 서버가 보관해야 하는 외부 토큰(애플 refresh_token 등)을 대칭키로 암호화한다.
 *
 * <p>DB 가 유출돼도 이 키 없이는 refresh_token 을 못 읽게 한다. revoke 에 쓸 값이라 평문 저장은
 * "탈퇴 대행" 권한을 DB 에 그대로 두는 셈이다.
 *
 * <p>AES-256-GCM 을 쓴다. GCM 은 무결성 태그를 함께 붙여 변조를 검출한다(CBC 는 그게 없다).
 * IV(nonce)는 매 암호화마다 새로 뽑는다 — 같은 키로 IV 를 재사용하면 GCM 은 평문을 노출한다.
 * 저장 형식은 {@code Base64(iv(12B) || ciphertext||tag)} 한 덩어리다.
 */
@Slf4j
@Component
public class SecretCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;      // GCM 권장 nonce 길이
    private static final int TAG_BITS = 128;     // 인증 태그 길이

    /** 키 미설정이면 null. encrypt/decrypt 호출 시에만 실패한다(부팅은 막지 않는다). */
    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    /**
     * 키가 비어 있어도 부팅은 막지 않는다(mTLS keystore 선례). .p8·키 미발급 상태로도 앱이 뜨게 하고,
     * 암호화가 실제로 필요한 애플 로그인 경로에서만 실패시킨다.
     */
    @Autowired
    public SecretCipher(@Value("${app.secret.enc-key:}") String base64Key) {
        this.key = decodeKeyOrNull(base64Key);
    }

    /** 테스트·명시 주입용. 32바이트(AES-256) 키를 직접 받는다. */
    public SecretCipher(byte[] keyBytes) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalStateException(
                    "SecretCipher 키는 32바이트(AES-256) 여야 한다");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    private static SecretKeySpec decodeKeyOrNull(String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[SECRET] app.secret.enc-key 미설정 — 애플 토큰 암호화는 실패한다. "
                    + "APP_SECRET_ENC_KEY(Base64 32바이트) 설정 필요");
            return null;
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Key.trim());
            if (bytes.length != 32) {
                throw new IllegalStateException(
                        "app.secret.enc-key 는 Base64 로 인코딩한 32바이트(AES-256) 여야 한다");
            }
            return new SecretKeySpec(bytes, "AES");
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("app.secret.enc-key 가 올바른 Base64 가 아니다.", e);
        }
    }

    private SecretKeySpec requireKey() {
        if (key == null) {
            throw new IllegalStateException(
                    "app.secret.enc-key 가 없다. APP_SECRET_ENC_KEY(Base64 32바이트) 를 설정해야 한다.");
        }
        return key;
    }

    /** 평문 → {@code Base64(iv || ciphertext||tag)}. 매 호출 새 IV. */
    public String encrypt(String plain) {
        if (plain == null) {
            throw new IllegalArgumentException("암호화할 평문이 null 이다");
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("암호화에 실패했다", e);
        }
    }

    /** {@code Base64(iv || ciphertext||tag)} → 평문. 변조됐으면 예외. */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) {
            throw new IllegalArgumentException("복호화할 값이 비어 있다");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) {
                throw new IllegalArgumentException("저장된 암호문 길이가 IV 보다 짧다");
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            byte[] ct = new byte[all.length - IV_BYTES];
            System.arraycopy(all, IV_BYTES, ct, 0, ct.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, requireKey(), new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ct);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("복호화에 실패했다", e);
        }
    }
}
