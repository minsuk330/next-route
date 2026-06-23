package watoo.grd.nextroute.infrastructure.adapter.in.api.notification;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.CancelBusAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateBusAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetBusAlertUseCase;

import java.util.List;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api/notifications/bus-alert")
@RequiredArgsConstructor
@Tag(name = "[회원] 버스 도착 알림")
public class BusAlertController {

    private final CreateBusAlertUseCase createBusAlertUseCase;
    private final GetBusAlertUseCase getBusAlertUseCase;
    private final CancelBusAlertUseCase cancelBusAlertUseCase;

    @PostMapping
    public ResponseEntity<BusAlertResponse> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody BusAlertRequest request) {
        return ResponseEntity.ok(createBusAlertUseCase.create(userId, request));
    }

    @GetMapping
    public ResponseEntity<List<BusAlertResponse>> getActive(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(getBusAlertUseCase.getActive(userId));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancel(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long id) {
        cancelBusAlertUseCase.cancel(userId, id);
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
}
