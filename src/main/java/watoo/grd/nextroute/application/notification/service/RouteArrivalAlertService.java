package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.CancelRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.TravelTimeQuery;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 목표 도착시간 기반 출발 알림 등록/취소/조회. 등록 시 1회 소요시간 추정으로 초기 leaveByAt 을 확정하고
 * DEPART_REMINDER outbox row 를 생성한다(PR1 은 dispatch 미존재 → row 는 inert). 재탐색·발송은 후속 PR.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteArrivalAlertService
        implements CreateRouteAlertUseCase, CancelRouteAlertUseCase, GetRouteAlertUseCase {

    private final RouteArrivalAlertRepository alertRepository;
    private final RouteArrivalAlertNotificationRepository notificationRepository;
    private final RouteTravelTimeEstimator estimator;
    private final UserRepository userRepository;
    private final RouteArrivalAlertProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public RouteAlertResponse create(long userId, RouteAlertRequest request) {
        User user = requireUser(userId);
        LocalDateTime now = LocalDateTime.now(clock);

        int userLead = request.getUserLeadMinutes();
        if (userLead % 5 != 0) {
            throw new IllegalArgumentException("userLeadMinutes 는 5분 단위여야 합니다: " + userLead);
        }
        if (!request.getTargetArrivalAt().isAfter(now)) {
            throw new IllegalArgumentException("목표 도착시각이 이미 지났습니다.");
        }
        int buffer = request.getBufferMinutes() != null
                ? request.getBufferMinutes()
                : properties.getDefaultBufferMinutes();

        int durationSec = estimator.estimateSeconds(new TravelTimeQuery(
                request.getStartX(), request.getStartY(), request.getStartName(),
                request.getEndX(), request.getEndY(), request.getEndName(), now));

        LocalDateTime leaveByAt = request.getTargetArrivalAt()
                .minusSeconds(durationSec)
                .minusMinutes(buffer);

        RouteArrivalAlert alert = alertRepository.save(RouteArrivalAlert.builder()
                .user(user)
                .startX(request.getStartX()).startY(request.getStartY()).startName(request.getStartName())
                .endX(request.getEndX()).endY(request.getEndY()).endName(request.getEndName())
                .targetArrivalAt(request.getTargetArrivalAt())
                .userLeadMinutes(userLead)
                .bufferMinutes(buffer)
                .lastTotalDurationSec(durationSec)
                .leaveByAt(leaveByAt)
                .lastResearchAt(now)
                .expiresAt(request.getTargetArrivalAt().plusMinutes(properties.getExpiresAfterTargetMinutes()))
                .build());

        // DEPART_REMINDER outbox row. 리마인드 시각이 이미 과거면 즉시 발송 대상(scheduledAt=now).
        LocalDateTime remindAt = leaveByAt.minusMinutes(userLead);
        if (remindAt.isBefore(now)) {
            remindAt = now;
        }
        notificationRepository.save(RouteArrivalAlertNotification.builder()
                .alert(alert)
                .type(RouteNotificationType.DEPART_REMINDER)
                .dedupKey(alert.getId() + ":DEPART")
                .scheduledAt(remindAt)
                .targetLeaveByAt(leaveByAt)
                .payload(null)
                .build());

        return RouteAlertResponse.from(alert);
    }

    @Override
    @Transactional
    public void cancel(long userId, Long alertId) {
        User user = requireUser(userId);
        RouteArrivalAlert alert = alertRepository.findByIdAndUserAndDeletedAtIsNull(alertId, user)
                .orElseThrow(() -> new NoSuchElementException("알림을 찾을 수 없습니다."));
        alert.markCanceled();
        // 취소 전파: 미종결 PENDING notification 을 CANCELED(§4.3).
        notificationRepository.cancelPendingByAlertId(alertId);
    }

    @Override
    public List<RouteAlertResponse> getActive(long userId) {
        User user = requireUser(userId);
        return alertRepository.findByUserAndDeletedAtIsNull(user).stream()
                .filter(a -> a.getStatus() == RouteAlertStatus.ACTIVE)
                .map(RouteAlertResponse::from)
                .toList();
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }
}
