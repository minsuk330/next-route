package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.port.in.WithdrawUseCase;

/**
 * 계정 탈퇴.
 *
 * <p>되돌릴 수 없다. 성공하면 204 를 준다. 이 요청 이후 같은 토큰으로 오는 재요청은 인증 가드가
 * 세대·활성 조건으로 걸러 401 이 된다(별도 처리 없음).
 */
@RestController
@RequestMapping("/api/auth/me")
@RequiredArgsConstructor
@Tag(name = "[앱] 계정 탈퇴")
public class WithdrawController {

    private final WithdrawUseCase withdrawUseCase;

    @DeleteMapping
    public ResponseEntity<Void> withdraw(@AuthenticationPrincipal Long userId) {
        withdrawUseCase.withdraw(userId);
        return ResponseEntity.noContent().build();
    }
}
