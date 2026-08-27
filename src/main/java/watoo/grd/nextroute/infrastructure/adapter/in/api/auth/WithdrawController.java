package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.port.in.WithdrawUseCase;
import watoo.grd.nextroute.application.auth.service.AppleRevocationOnWithdrawal;

/**
 * 계정 탈퇴.
 *
 * <p>되돌릴 수 없다. 성공하면 204 를 준다. 이 요청 이후 같은 토큰으로 오는 재요청은 인증 가드가
 * 세대·활성 조건으로 걸러 401 이 된다(별도 처리 없음).
 *
 * <p>애플 로그인 사용자면 삭제 <b>전</b>에 애플 연결을 끊는다(revoke). 삭제 트랜잭션이
 * apple_user_token 을 지우기 전에 그 토큰을 써야 하고, 네트워크 호출을 단일 tx 삭제 밖에 두어
 * {@link WithdrawUseCase} 의 "외부 호출 0" 계약을 지키기 위해 여기서 부른다. best-effort 라 실패해도
 * 탈퇴는 그대로 진행된다.
 */
@RestController
@RequestMapping("/api/auth/me")
@RequiredArgsConstructor
@Tag(name = "[앱] 계정 탈퇴")
public class WithdrawController {

    private final WithdrawUseCase withdrawUseCase;
    private final AppleRevocationOnWithdrawal appleRevocationOnWithdrawal;

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userId) {
        appleRevocationOnWithdrawal.revokeIfApple(userId); // 삭제 트랜잭션 앞(밖). best-effort.
        withdrawUseCase.withdraw(userId);
        return ResponseEntity.noContent().build();
    }
}
