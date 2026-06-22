package watoo.grd.nextroute.infrastructure.adapter.in.api.auth;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;
import watoo.grd.nextroute.application.auth.port.in.TossLoginUseCase;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * 토스 연결끊기 콜백 수신. 사용자가 토스앱에서 직접 연결을 끊을 때 호출된다.
 * 콘솔에 등록한 Basic Auth로 보호한다. GET/POST 모두 지원.
 */
@Slf4j
@RestController
@RequestMapping("/api/auth/toss/unlink-callback")
@RequiredArgsConstructor
@Tag(name = "[내부] 토스 연결끊기 콜백")
public class TossUnlinkCallbackController {

    private final TossLoginUseCase tossLoginUseCase;
    private final TossLoginProperties properties;

    @GetMapping
    public ResponseEntity<Void> callbackGet(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam long userKey,
            @RequestParam String referrer) {
        return handle(auth, userKey, referrer);
    }

    @PostMapping
    public ResponseEntity<Void> callbackPost(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestBody Map<String, Object> body) {
        long userKey = Long.parseLong(String.valueOf(body.get("userKey")));
        String referrer = String.valueOf(body.get("referrer"));
        return handle(auth, userKey, referrer);
    }

    private ResponseEntity<Void> handle(String auth, long userKey, String referrer) {
        if (!isAuthorized(auth)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        tossLoginUseCase.handleUnlinkCallback(userKey, referrer);
        return ResponseEntity.ok().build();
    }

    private boolean isAuthorized(String authHeader) {
        String user = properties.getCallbackUsername();
        String pass = properties.getCallbackPassword();
        if (user == null || user.isBlank()) {
            log.warn("[TOSS] 콜백 Basic Auth 미설정 — 모든 콜백 거부");
            return false;
        }
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }
        String expected = Base64.getEncoder()
                .encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
        return ("Basic " + expected).equals(authHeader);
    }
}
