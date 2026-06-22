package watoo.grd.nextroute.infrastructure.adapter.out.api.toss;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class TossCryptoServiceTest {

    private static final String AAD = "TOSS";

    @Test
    void decrypt_roundtrip() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String base64Key = Base64.getEncoder().encodeToString(keyBytes);

        TossLoginProperties props = new TossLoginProperties();
        props.setDecryptKey(base64Key);
        props.setAad(AAD);
        TossCryptoService service = new TossCryptoService(props);

        String plain = "홍길동";
        String encrypted = encrypt(plain, keyBytes, AAD);

        assertThat(service.decrypt(encrypted)).isEqualTo(plain);
    }

    @Test
    void decrypt_nullOrBlank_passthrough() {
        TossLoginProperties props = new TossLoginProperties();
        props.setDecryptKey(Base64.getEncoder().encodeToString(new byte[32]));
        TossCryptoService service = new TossCryptoService(props);

        assertThat(service.decrypt(null)).isNull();
        assertThat(service.decrypt("")).isEmpty();
    }

    private static String encrypt(String plain, byte[] keyBytes, String aad) {
        try {
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"),
                    new GCMParameterSpec(128, iv));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
