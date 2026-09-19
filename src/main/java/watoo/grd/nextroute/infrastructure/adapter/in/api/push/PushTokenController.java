package watoo.grd.nextroute.infrastructure.adapter.in.api.push;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.push.port.in.PushTokenUseCase;
import watoo.grd.nextroute.infrastructure.adapter.in.api.push.dto.RegisterPushTokenRequest;
import watoo.grd.nextroute.infrastructure.adapter.in.api.push.dto.UnregisterPushTokenRequest;

/**
 * 푸시 token 등록·해제.
 *
 * <p>token 은 URL 에 담지 않는다. 경로와 쿼리는 접근 로그·프록시 로그에 그대로 남는다.
 * DELETE 가 쿼리 파라미터 대신 본문을 받는 이유가 그것이다(REQUIREMENTS §6).
 *
 * <p>사용자 ID 는 JWT 에서만 온다. 본문으로 받으면 남의 계정에 token 을 심을 수 있다.
 */
@RestController
@RequestMapping("/api/me/push-token")
@RequiredArgsConstructor
@Tag(name = "[앱] 푸시 토큰")
public class PushTokenController {

    private final PushTokenUseCase pushTokenUseCase;

    @Operation(summary = "푸시 토큰 등록·교체",
            description = "provider 가 token 을 갱신할 때마다 앱이 다시 호출한다. 같은 token 이 다른 "
                    + "사용자에게 등록되면 앞선 소유자에게서 떼어 낸다.")
    @PutMapping
    public ResponseEntity<Void> register(@AuthenticationPrincipal Long userId,
                                         @Valid @RequestBody RegisterPushTokenRequest request) {
        pushTokenUseCase.register(userId, request.toCommand());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "푸시 토큰 해제",
            description = "로그아웃 시 호출한다. 없는 token 이거나 남의 token 이어도 204 다(멱등).")
    @DeleteMapping
    public ResponseEntity<Void> unregister(@AuthenticationPrincipal Long userId,
                                           @Valid @RequestBody UnregisterPushTokenRequest request) {
        pushTokenUseCase.unregister(userId, request.token());
        return ResponseEntity.noContent().build();
    }
}
