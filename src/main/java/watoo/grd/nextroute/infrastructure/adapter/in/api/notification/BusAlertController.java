package watoo.grd.nextroute.infrastructure.adapter.in.api.notification;

import java.util.Map;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpStatus;
import watoo.grd.nextroute.application.notification.exception.TossIdentityRequiredException;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.BusAlertUseCase;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/notifications/bus-alert")
@RequiredArgsConstructor
@Tag(name = "[회원] 버스 도착 알림")
public class BusAlertController {

    private final BusAlertUseCase busAlertUseCase;

    @PostMapping
    public ResponseEntity<BusAlertResponse> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody BusAlertRequest request) {
        return ResponseEntity.ok(busAlertUseCase.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<BusAlertResponse>> getActive(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(busAlertUseCase.getActive(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        busAlertUseCase.cancel(userId, id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Void> handleBadRequest() {
        return ResponseEntity.badRequest().build();
    }

    /**
     * 알림 발송 채널이 토스뿐이라 토스 신원이 없으면 구독을 만들 수 없다(Phase 5 FCM 도입 시 해제).
     * 400 이 아니라 422 인 이유는 요청 형식은 맞고 계정 상태가 조건을 못 채운 것이기 때문이다.
     */
    @ExceptionHandler(TossIdentityRequiredException.class)
    public ResponseEntity<Map<String, Object>> handleTossIdentityRequired(TossIdentityRequiredException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of(
                "error", true,
                "code", "TOSS_IDENTITY_REQUIRED",
                "message", e.getMessage()));
    }
}
