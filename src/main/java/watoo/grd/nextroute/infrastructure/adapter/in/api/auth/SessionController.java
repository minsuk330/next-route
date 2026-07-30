package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.exception.SessionException;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.ReissueRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.TokenResponse;

import java.util.Map;

/**
 * 앱 세션(애플·카카오 로그인 사용자).
 *
 * <p>토스 미니앱은 {@code /api/auth/toss/*} 를 계속 쓴다. 그쪽 로그아웃은 세션 종료가 아니라
 * 토스 연결 끊기라서, 여기의 로그아웃과 뜻이 다르다.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "[앱] 세션")
public class SessionController {

    private final SessionUseCase sessionUseCase;

    /** 인증 불필요 — access 가 만료된 뒤 부르는 경로다. 본문의 refresh 토큰이 신원을 증명한다. */
    @PostMapping("/reissue")
    public ResponseEntity<TokenResponse> reissue(@Valid @RequestBody ReissueRequest request) {
        return ResponseEntity.ok(TokenResponse.from(sessionUseCase.reissue(request.refreshToken())));
    }

    /**
     * 로그아웃. 이 사용자에게 발급된 access·refresh 가 <b>즉시</b> 전부 무효가 된다.
     *
     * <p>클라이언트가 저장소를 비우는 것만으로는 부족하다. 이미 나간 토큰은 만료까지 계속 통한다.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long userId) {
        sessionUseCase.logout(userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<Map<String, Object>> handleSession(SessionException e) {
        // 왜 거부됐는지는 싣지 않는다. 앱이 할 일은 어느 쪽이든 재로그인이다.
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", true,
                "code", "SESSION_INVALID",
                "message", "다시 로그인해야 한다"));
    }
}
