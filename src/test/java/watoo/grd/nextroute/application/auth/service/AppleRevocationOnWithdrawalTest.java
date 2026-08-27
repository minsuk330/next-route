package watoo.grd.nextroute.application.auth.service;

import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.auth.port.out.AppleRevokePort;
import watoo.grd.nextroute.common.security.SecretCipher;
import watoo.grd.nextroute.domain.auth.entity.AppleUserToken;
import watoo.grd.nextroute.domain.auth.repository.AppleUserTokenRepository;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class AppleRevocationOnWithdrawalTest {

    private final AppleUserTokenRepository repository = mock(AppleUserTokenRepository.class);
    private final AppleRevokePort revokePort = mock(AppleRevokePort.class);
    private final SecretCipher cipher = mock(SecretCipher.class);

    private final AppleRevocationOnWithdrawal service =
            new AppleRevocationOnWithdrawal(repository, revokePort, cipher);

    @Test
    void revokeIfApple_tokenExists_decryptsAndRevokes() {
        AppleUserToken token = AppleUserToken.builder()
                .userId(1L).refreshTokenEnc("enc").appleSub("sub").build();
        given(repository.findByUserId(1L)).willReturn(Optional.of(token));
        given(cipher.decrypt("enc")).willReturn("plain-refresh");

        service.revokeIfApple(1L);

        then(revokePort).should().revoke("plain-refresh");
    }

    @Test
    void revokeIfApple_noToken_doesNothing() {
        given(repository.findByUserId(2L)).willReturn(Optional.empty());

        service.revokeIfApple(2L);

        then(revokePort).shouldHaveNoInteractions();
        then(cipher).shouldHaveNoInteractions();
    }

    @Test
    void revokeIfApple_revokeFails_swallowsException() {
        AppleUserToken token = AppleUserToken.builder()
                .userId(3L).refreshTokenEnc("enc").build();
        given(repository.findByUserId(3L)).willReturn(Optional.of(token));
        given(cipher.decrypt("enc")).willReturn("plain");
        doThrow(new RuntimeException("애플 500")).when(revokePort).revoke(anyString());

        // 탈퇴를 막지 않으려면 예외가 밖으로 새면 안 된다.
        assertThatCode(() -> service.revokeIfApple(3L)).doesNotThrowAnyException();
    }
}
