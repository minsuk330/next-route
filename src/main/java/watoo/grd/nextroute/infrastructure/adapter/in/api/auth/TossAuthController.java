package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.auth.exception.TossLoginException;
import watoo.grd.nextroute.application.auth.port.in.TossLoginUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.TossLoginRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.TossLoginResponse;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.TossReissueRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.auth.dto.TossTokenResponse;

@RestController
@RequestMapping("/api/auth/toss")
@RequiredArgsConstructor
@Tag(name = "[공용] 토스 로그인")
public class TossAuthController {

    private final TossLoginUseCase tossLoginUseCase;

    /** 미니앱 appLogin()으로 받은 인가코드로 로그인. 신원은 토스 사용자 식별키로 식별. */
    @PostMapping("/login")
    public ResponseEntity<TossLoginResponse> login(
            @Valid @RequestBody TossLoginRequest request) {
        var result = tossLoginUseCase.login(request.getAuthorizationCode(), request.getReferrer());
        return ResponseEntity.ok(TossLoginResponse.from(result));
    }

    /** 자체 refresh JWT로 access/refresh 재발급. */
    @PostMapping("/reissue")
    public ResponseEntity<TossTokenResponse> reissue(@Valid @RequestBody TossReissueRequest request) {
        var result = tossLoginUseCase.reissue(request.getRefreshToken());
        return ResponseEntity.ok(TossTokenResponse.from(result));
    }

    /** 로그아웃(토스 연결 끊기). Bearer 토큰 필요(SecurityConfig에서 authenticated 강제). */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal Long userId) {
        tossLoginUseCase.logout(userId);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(TossLoginException.class)
    public ResponseEntity<String> handleTossLogin(TossLoginException e) {
        HttpStatus status = e.isInvalidGrant() ? HttpStatus.UNAUTHORIZED : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(e.getMessage());
    }
}
