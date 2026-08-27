package watoo.grd.nextroute.application.auth.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.auth.port.out.AppleRevokePort;
import watoo.grd.nextroute.common.security.SecretCipher;
import watoo.grd.nextroute.domain.auth.repository.AppleUserTokenRepository;

/**
 * 탈퇴 직전, 애플 로그인 사용자면 애플 연결을 끊는다(revoke).
 *
 * <p><b>왜 삭제 트랜잭션 밖·앞인가</b>: {@link WithdrawService} 는 "외부 호출 0, 단일 트랜잭션"을
 * 계약으로 지킨다(그래야 전부 벌크 삭제가 안전하다). 애플 revoke 는 네트워크 호출이라 그 안에 넣으면
 * 계약이 깨진다. 그래서 탈퇴 유스케이스 <b>앞</b>에서 한 번 부르고, {@code WithdrawService} 는 그대로
 * apple_user_token 을 물리 삭제한다.
 *
 * <p><b>best-effort</b>: 애플이 죽어 있어도 탈퇴는 막지 않는다. 실패하면 로그만 남긴다. 재시도·outbox
 * 없음(탈퇴당 1회, 저빈도라 과하다). 토큰이 없으면(애플 로그인 아님) 조용히 넘어간다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppleRevocationOnWithdrawal {

    private final AppleUserTokenRepository appleUserTokenRepository;
    private final AppleRevokePort appleRevokePort;
    private final SecretCipher secretCipher;

    /**
     * 이 사용자에게 저장된 애플 refresh_token 이 있으면 revoke 를 부른다.
     * 어떤 실패도 삼킨다 — 호출부(탈퇴)는 이 결과와 무관하게 진행한다.
     */
    public void revokeIfApple(long userId) {
        appleUserTokenRepository.findByUserId(userId).ifPresent(token -> {
            try {
                appleRevokePort.revoke(secretCipher.decrypt(token.getRefreshTokenEnc()));
                log.info("[AppleRevoke] 연결 폐기 성공: userId={}", userId);
            } catch (Exception e) {
                // 탈퇴를 막지 않는다. 유실돼도 심사 재제출 시 재호출되거나 애플이 자체 만료한다.
                log.warn("[AppleRevoke] 폐기 실패(무시, 탈퇴는 진행): userId={}", userId, e);
            }
        });
    }
}
