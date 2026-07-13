package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchStateService;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchStateService.RequeueResult;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * DEAD(재시도 소진) 경로 알림 outbox 조회·재투입. X-Admin-Key 인증은 AdminApiKeyFilter 가 처리.
 */
@RestController
@RequestMapping("/api/admin/route-alert-notifications")
@RequiredArgsConstructor
@Tag(name = "[어드민] 경로 알림 발송 outbox")
public class RouteAlertNotificationAdminController {

    private final RouteAlertDispatchStateService stateService;

    public record DeadRow(long id, String dedupKey, int attemptCount, int requeueCount,
                          String lastFailureReason, LocalDateTime createdAt) {
    }

    /** DEAD 재투입 요청. ids 생략(null) 시 전체 DEAD 대상. resetAttempts=true 면 재시도 예산 전체 재부여. */
    public record RequeueRequest(List<Long> ids, boolean resetAttempts) {
    }

    @GetMapping("/dead")
    @Operation(summary = "DEAD outbox 목록", description = "재시도 소진된 발송 row(최신순).")
    public ResponseEntity<List<DeadRow>> listDead() {
        List<DeadRow> rows = stateService.findDead().stream().map(this::toRow).toList();
        return ResponseEntity.ok(rows);
    }

    @PostMapping("/dead/requeue")
    @Operation(summary = "DEAD outbox 재투입",
            description = "ids 미지정 시 전체 DEAD. 부모 ACTIVE·미래 target·requeue 상한 미만인 것만 PENDING 복귀.")
    public ResponseEntity<Map<String, Object>> requeueDead(
            @RequestBody(required = false) RequeueRequest request) {
        boolean reset = request != null && request.resetAttempts();
        List<Long> ids = (request != null && request.ids() != null)
                ? request.ids()
                : stateService.findDead().stream().map(RouteArrivalAlertNotification::getId).toList();

        RequeueResult result = stateService.requeueDead(ids, reset);
        return ResponseEntity.ok(Map.of(
                "requeued", result.requeued(),
                "skipped", result.skipped()));
    }

    private DeadRow toRow(RouteArrivalAlertNotification n) {
        return new DeadRow(n.getId(), n.getDedupKey(), n.getAttemptCount(),
                n.getRequeueCount(), n.getLastFailureReason(), n.getCreatedAt());
    }
}
