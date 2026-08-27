package watoo.grd.nextroute.common.security;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretCipherTest {

    private static byte[] key256() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTrip_returnsOriginal() {
        SecretCipher cipher = new SecretCipher(key256());

        String plain = "apple-refresh-token-abc.def.ghi";
        String enc = cipher.encrypt(plain);

        assertThat(enc).isNotEqualTo(plain);
        assertThat(cipher.decrypt(enc)).isEqualTo(plain);
    }

    @Test
    void samePlaintext_differentIv_producesDifferentCiphertext() {
        SecretCipher cipher = new SecretCipher(key256());

        String plain = "same-input";
        String a = cipher.encrypt(plain);
        String b = cipher.encrypt(plain);

        // 매 호출 IV 가 달라 암호문도 달라야 한다(GCM 의 IV 재사용은 평문을 노출한다).
        assertThat(a).isNotEqualTo(b);
        assertThat(cipher.decrypt(a)).isEqualTo(plain);
        assertThat(cipher.decrypt(b)).isEqualTo(plain);
    }

    @Test
    void tamperedCiphertext_isRejected() {
        SecretCipher cipher = new SecretCipher(key256());
        String enc = cipher.encrypt("payload");

        // 마지막 글자를 바꿔 무결성 태그를 깬다.
        char last = enc.charAt(enc.length() - 1);
        String tampered = enc.substring(0, enc.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> cipher.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongKeyLength_isRejected() {
        assertThatThrownBy(() -> new SecretCipher(new byte[16]))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void missingKey_bootsButFailsOnUse() {
        // 빈 키로도 생성은 된다(부팅 허용). 실제 암호화에서만 실패한다.
        SecretCipher cipher = new SecretCipher("");

        assertThatThrownBy(() -> cipher.encrypt("x"))
                .isInstanceOf(IllegalStateException.class);
    }
}
