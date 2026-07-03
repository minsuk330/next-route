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
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.TravelTimeQuery;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;

/**
 * 목표 도착시간 기반 출발 알림 v2 등록/취소/조회. 등록 시 STATIC evaluator 로 baseline 을 확정하고
 * first check 예정시각을 잡아 WAITING_FIRST_CHECK 로 저장한다. first check·발송은 후속 PR(스케줄러/디스패처).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RouteArrivalAlertService
        implements CreateRouteAlertUseCase, CancelRouteAlertUseCase, GetRouteAlertUseCase {

    private static final Set<Integer> ALLOWED_LEAD = Set.of(0, 5, 10, 15);

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
        if (!ALLOWED_LEAD.contains(userLead)) {
            throw new IllegalArgumentException("userLeadMinutes 는 {0,5,10,15} 중 하나여야 합니다: " + userLead);
        }
        if (!request.getTargetArrivalAt().isAfter(now)) {
            throw new IllegalArgumentException("목표 도착시각이 이미 지났습니다.");
        }
        int buffer = request.getBufferMinutes() != null
                ? request.getBufferMinutes()
                : properties.getDefaultBufferMinutes();

        // baseline: STATIC 추정(실시간 미반영, term/2 정적 대기).
        RouteEvaluation eval = estimator.evaluate(new TravelTimeQuery(
                request.getStartX(), request.getStartY(), request.getStartName(),
                request.getEndX(), request.getEndY(), request.getEndName(),
                request.getTargetArrivalAt()), EvaluationMode.STATIC);

        LocalDateTime baselineLeaveByAt = request.getTargetArrivalAt()
                .minusSeconds(eval.durationSec())
                .minusMinutes(buffer);
        if (!baselineLeaveByAt.isAfter(now)) {
            throw new IllegalStateException("목표 도착시각에 맞추려면 이미 출발했어야 합니다.");
        }

        // first check 여유 = 기본 + term 비례(배차 변동 흡수, 상한). baseline 낙관 편향 흡수.
        int maxHeadway = eval.maxHeadwayMinutes() != null ? eval.maxHeadwayMinutes() : 0;
        int headwayMargin = Math.min(
                (int) Math.round(maxHeadway * properties.getFirstCheckHeadwayFactor()),
                properties.getFirstCheckMaxMarginMinutes());
        int safetyMargin = properties.getFirstCheckBaseMarginMinutes() + headwayMargin;
        LocalDateTime firstCheckAt = baselineLeaveByAt.minusMinutes(userLead).minusMinutes(safetyMargin);
        if (firstCheckAt.isBefore(now)) {
            firstCheckAt = now;
        }

        RouteArrivalAlert alert = alertRepository.save(RouteArrivalAlert.builder()
                .user(user)
                .startX(request.getStartX()).startY(request.getStartY()).startName(request.getStartName())
                .endX(request.getEndX()).endY(request.getEndY()).endName(request.getEndName())
                .targetArrivalAt(request.getTargetArrivalAt())
                .userLeadMinutes(userLead)
                .bufferMinutes(buffer)
                .baselineDurationSec(eval.durationSec())
                .baselineLeaveByAt(baselineLeaveByAt)
                .firstCheckAt(firstCheckAt)
                .reliability(eval.reliability())
                .expiresAt(request.getTargetArrivalAt().plusMinutes(properties.getExpiresAfterTargetMinutes()))
                .build());

        // DEPARTURE row 는 first check(PR-B)가 생성. 등록 시점엔 만들지 않음.
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
