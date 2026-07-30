package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.exception.SessionException;
import watoo.grd.nextroute.application.auth.port.in.SessionUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.MeResponse;

import java.util.Map;

/**
 * 내 정보.
 *
 * <p>애플은 이름을 최초 1회만 준다. 앱이 그 값을 다시 가져올 수 있는 창구가 여기다.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
@Tag(name = "[앱] 내 정보")
public class MeController {

    private final SessionUseCase sessionUseCase;

    @GetMapping
    public ResponseEntity<MeResponse> me(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(MeResponse.from(sessionUseCase.me(userId)));
    }

    @ExceptionHandler(SessionException.class)
    public ResponseEntity<Map<String, Object>> handleSession(SessionException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                "error", true,
                "code", "SESSION_INVALID",
                "message", "다시 로그인해야 한다"));
    }
}
