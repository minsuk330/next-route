package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import watoo.grd.nextroute.application.journey.service.AdminJourneyNotificationTestService;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;

/** 관리자 키로 FCM 여정 알림 수신을 확인하는 테스트 API. */
@RestController
@RequestMapping("/api/admin/notifications")
@RequiredArgsConstructor
@Tag(name = "[어드민] 알림 테스트")
public class AdminNotificationTestController {

    private final AdminJourneyNotificationTestService testService;

    @PostMapping("/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(
            summary = "여정 FCM 테스트 알림 enqueue",
            description = "활성 Journey의 최신 push token으로 운영 relay와 동일한 FCM 경로를 시험한다.")
    public QueuedNotificationResponse enqueue(@Valid @RequestBody TestNotificationRequest request) {
        AdminJourneyNotificationTestService.QueuedTestNotification queued = testService.enqueue(
                request.journeyId(), request.checkpoint());
        return QueuedNotificationResponse.from(queued);
    }

    public record TestNotificationRequest(
            @NotNull @Positive Long journeyId,
            @NotNull JourneyCheckpoint checkpoint) {
    }

    public record QueuedNotificationResponse(
            long notificationId,
            long journeyId,
            JourneyCheckpoint checkpoint,
            java.time.Instant queuedAt,
            java.time.Instant expiresAt) {

        static QueuedNotificationResponse from(
                AdminJourneyNotificationTestService.QueuedTestNotification queued) {
            return new QueuedNotificationResponse(
                    queued.notificationId(), queued.journeyId(), queued.checkpoint(),
                    queued.queuedAt(), queued.expiresAt());
        }
    }
}
