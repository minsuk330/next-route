package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.exception.DeviceAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkLimitExceededException;
import watoo.grd.nextroute.application.auth.exception.DeviceLinkNotAllowedException;
import watoo.grd.nextroute.application.auth.exception.DeviceSignupRateLimitedException;
import watoo.grd.nextroute.application.auth.exception.InvalidDeviceSecretException;
import watoo.grd.nextroute.application.auth.port.in.DeviceLoginUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.DeviceLoginRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.OAuthLoginResponse;

import java.util.Map;

/**
 * 기기 익명 계정(독립 앱). 로그인 화면 없이 앱이 만든 비밀값으로 토큰을 받는다.
 *
 * <p>공개 경로다({@code SecurityConfig} 는 적어 둔 경로만 보호한다). 유효한 Bearer 가 붙어 오면 필터가 principal 을
 * 세팅하므로 그 계정에 기기를 연결하고, 무효한 Bearer 는 익명으로 떨어진다. 갱신은 {@code /api/auth/reissue}.
 * 미니앱은 이 경로를 쓰지 않는다({@code /api/auth/toss/*}).
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "[공용] 기기 익명 계정")
public class DeviceAuthController {

    /** nginx 가 {@code $remote_addr} 로 덮어쓴다. X-Forwarded-For 는 클라이언트가 위조할 수 있어 읽지 않는다. */
    static final String REAL_IP_HEADER = "X-Real-IP";

    private final DeviceLoginUseCase deviceLoginUseCase;

    @PostMapping("/device")
    public ResponseEntity<OAuthLoginResponse> device(
            @RequestBody DeviceLoginRequest request,
            @AuthenticationPrincipal Long userId,
            HttpServletRequest httpRequest
    ) {
        var result = deviceLoginUseCase.login(request.getDeviceSecret(), userId, clientIp(httpRequest));
        return ResponseEntity.ok(OAuthLoginResponse.from(result));
    }

    static String clientIp(HttpServletRequest request) {
        String realIp = request.getHeader(REAL_IP_HEADER);
        return (realIp == null || realIp.isBlank()) ? request.getRemoteAddr() : realIp.trim();
    }

    @ExceptionHandler(InvalidDeviceSecretException.class)
    public ResponseEntity<Map<String, Object>> handleInvalid(InvalidDeviceSecretException e) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_SECRET", e.getMessage());
    }

    @ExceptionHandler(DeviceLinkNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleLinkNotAllowed(DeviceLinkNotAllowedException e) {
        return error(HttpStatus.FORBIDDEN, "DEVICE_LINK_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(DeviceAlreadyLinkedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyLinked(DeviceAlreadyLinkedException e) {
        return error(HttpStatus.CONFLICT, "DEVICE_ALREADY_LINKED", e.getMessage());
    }

    @ExceptionHandler(DeviceLinkLimitExceededException.class)
    public ResponseEntity<Map<String, Object>> handleLinkLimit(DeviceLinkLimitExceededException e) {
        return error(HttpStatus.CONFLICT, "DEVICE_LINK_LIMIT_EXCEEDED", e.getMessage());
    }

    @ExceptionHandler(DeviceSignupRateLimitedException.class)
    public ResponseEntity<Map<String, Object>> handleRateLimited(DeviceSignupRateLimitedException e) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "DEVICE_SIGNUP_RATE_LIMITED", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", true,
                "code", code,
                "message", message));
    }
}
