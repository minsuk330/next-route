package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.auth.config.OAuthProviderProperties;
import watoo.grd.nextroute.application.auth.dto.OAuthCredential;
import watoo.grd.nextroute.application.auth.exception.IdentityAlreadyLinkedException;
import watoo.grd.nextroute.application.auth.exception.OAuthVerificationException;
import watoo.grd.nextroute.application.auth.exception.ProviderNotAllowedException;
import watoo.grd.nextroute.application.auth.port.in.OAuthLoginUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.OAuthChallengeResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.OAuthLoginRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.OAuthLoginResponse;

import java.util.Map;

/**
 * 앱 OAuth 로그인(애플·카카오).
 *
 * <p>토스 미니앱 로그인은 {@code /api/auth/toss/*} 가 계속 담당한다. 앱은 그 경로를 호출하지 않는다.
 */
@RestController
@RequestMapping("/api/auth/oauth")
@RequiredArgsConstructor
@Tag(name = "[공용] 앱 OAuth 로그인")
public class OAuthController {

    private final OAuthLoginUseCase oAuthLoginUseCase;
    private final OAuthProviderProperties properties;

    /**
     * 애플 로그인에 쓸 일회용 값을 발급한다.
     *
     * <p>앱이 만든 값을 그대로 돌려받아 비교하면 훔친 토큰의 재전송을 막지 못한다.
     * 서버가 발급하고 로그인 때 한 번만 소비해야 "일회용"이 성립한다.
     */
    @PostMapping("/challenge")
    public ResponseEntity<OAuthChallengeResponse> challenge() {
        return ResponseEntity.ok(new OAuthChallengeResponse(
                oAuthLoginUseCase.issueChallenge(),
                properties.getChallenge().getTtlSeconds()));
    }

    /** 처음 보는 신원이면 가입까지 겸한다. */
    @PostMapping("/login")
    public ResponseEntity<OAuthLoginResponse> login(@Valid @RequestBody OAuthLoginRequest request) {
        var result = oAuthLoginUseCase.login(new OAuthCredential(
                request.getProvider(), request.getCredential(),
                request.getNonce(), request.getDisplayName()));
        return ResponseEntity.ok(OAuthLoginResponse.from(result));
    }

    /** 어느 항목이 틀렸는지는 응답에 싣지 않는다. 공격자에게 힌트가 된다. */
    @ExceptionHandler(OAuthVerificationException.class)
    public ResponseEntity<Map<String, Object>> handleVerification(OAuthVerificationException e) {
        return error(HttpStatus.UNAUTHORIZED, "OAUTH_VERIFICATION_FAILED", "로그인 검증에 실패했다");
    }

    @ExceptionHandler(ProviderNotAllowedException.class)
    public ResponseEntity<Map<String, Object>> handleProviderNotAllowed(ProviderNotAllowedException e) {
        return error(HttpStatus.BAD_REQUEST, "PROVIDER_NOT_ALLOWED", e.getMessage());
    }

    @ExceptionHandler(IdentityAlreadyLinkedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyLinked(IdentityAlreadyLinkedException e) {
        return error(HttpStatus.CONFLICT, "IDENTITY_ALREADY_LINKED", e.getMessage());
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of(
                "error", true,
                "code", code,
                "message", message));
    }
}
