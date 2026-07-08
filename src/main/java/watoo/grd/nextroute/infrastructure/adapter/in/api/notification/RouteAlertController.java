package watoo.grd.nextroute.infrastructure.adapter.in.api.notification;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.application.notification.dto.RouteAlertUpdateRequest;
import watoo.grd.nextroute.application.notification.exception.ScheduleNotEditableException;
import watoo.grd.nextroute.application.notification.port.in.CancelRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.UpdateRouteAlertUseCase;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/notifications/route-alert")
@RequiredArgsConstructor
@Tag(name = "[회원] 목표 도착시간 출발 알림")
public class RouteAlertController {

    private final CreateRouteAlertUseCase createRouteAlertUseCase;
    private final UpdateRouteAlertUseCase updateRouteAlertUseCase;
    private final GetRouteAlertUseCase getRouteAlertUseCase;
    private final CancelRouteAlertUseCase cancelRouteAlertUseCase;

    @PostMapping
    public ResponseEntity<RouteAlertScheduleResponse> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody RouteAlertRequest request) {
        return ResponseEntity.ok(createRouteAlertUseCase.create(userId, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<RouteAlertScheduleResponse> update(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id,
            @Valid @RequestBody RouteAlertUpdateRequest request) {
        return ResponseEntity.ok(updateRouteAlertUseCase.update(userId, id, request));
    }

    @GetMapping
    public ResponseEntity<List<RouteAlertScheduleResponse>> getActive(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(getRouteAlertUseCase.getActive(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        cancelRouteAlertUseCase.cancel(userId, id);
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

    /** 소요시간 추정 실패(경로 없음 등): 요청은 유효하나 처리 불가 → 422. */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Void> handleUnprocessable() {
        return ResponseEntity.unprocessableEntity().build();
    }

    /** 비-ACTIVE 스케줄 수정 시도·동시 수정 충돌 → 409. */
    @ExceptionHandler(ScheduleNotEditableException.class)
    public ResponseEntity<Void> handleConflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
}
