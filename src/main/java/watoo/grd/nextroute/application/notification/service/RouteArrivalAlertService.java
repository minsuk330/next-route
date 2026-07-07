package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.application.notification.port.in.CancelRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetRouteAlertUseCase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteWeekdays;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 반복/일회성 목표 도착 알림 구독 등록/취소/조회(API orchestration). 구독 규칙은 {@link RouteAlertSchedule}로 저장하고,
 * occurrence materialize 는 {@link RouteAlertScheduleSpawnService}에 위임한다(tx 경계 분리 — self-invocation 프록시 회피).
 * <p>등록은 schedule 저장(tx1) → 즉시 spawn 시도(tx2)로 나눠, occurrence 생성 실패가 schedule 저장을 rollback 하지 않게 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteArrivalAlertService
        implements CreateRouteAlertUseCase, CancelRouteAlertUseCase, GetRouteAlertUseCase {

    private static final Set<Integer> ALLOWED_LEAD = Set.of(0, 5, 10, 15);

    private final RouteAlertScheduleRepository scheduleRepository;
    private final RouteArrivalAlertRepository alertRepository;
    private final RouteArrivalAlertNotificationRepository notificationRepository;
    private final RouteAlertScheduleSpawnService spawnService;
    private final UserRepository userRepository;
    private final RouteArrivalAlertProperties properties;
    private final Clock clock;

    @Override
    public RouteAlertScheduleResponse create(long userId, RouteAlertRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);

        int userLead = request.getUserLeadMinutes();
        if (!ALLOWED_LEAD.contains(userLead)) {
            throw new IllegalArgumentException("userLeadMinutes 는 {0,5,10,15} 중 하나여야 합니다: " + userLead);
        }
        LocalTime arrivalTime = request.getArrivalTime();
        if (arrivalTime == null) {
            throw new IllegalArgumentException("arrivalTime 이 필요합니다.");
        }
        int mask = RouteWeekdays.maskFromCodes(request.getWeekdays());
        if (request.isRepeat() && mask == 0) {
            throw new IllegalArgumentException("반복(repeat=true) 은 weekdays 를 1개 이상 지정해야 합니다.");
        }
        int buffer = request.getBufferMinutes() != null
                ? request.getBufferMinutes()
                : properties.getDefaultBufferMinutes();

        User user = requireUser(userId);

        // tx1: schedule 저장(자체 커밋). 이후 spawn 실패가 이 저장을 rollback 하지 않는다.
        RouteAlertSchedule saved = scheduleRepository.save(RouteAlertSchedule.builder()
                .user(user)
                .startX(request.getStartX()).startY(request.getStartY()).startName(request.getStartName())
                .endX(request.getEndX()).endY(request.getEndY()).endName(request.getEndName())
                .arrivalTime(arrivalTime)
                .weekdaysMask(mask)
                .repeat(request.isRepeat())
                .userLeadMinutes(userLead)
                .bufferMinutes(buffer)
                .build());

        // tx2: window 내면 즉시 occurrence 생성(SpawnService 가 in-flight/window/roll-forward 판정).
        // 즉시 materialize 가 예상외로 실패해도(외부 API RuntimeException 등) schedule(tx1) 은 유지 —
        // 응답은 계산된 다음 도래시각으로 반환하고, occurrence 는 spawner/다음 스캔이 채운다.
        try {
            spawnService.processScheduleSpawn(saved.getId(), now);
        } catch (RuntimeException e) {
            log.warn("[RouteAlert] 즉시 occurrence 생성 실패 scheduleId={}: {}", saved.getId(), e.getMessage());
        }

        return buildResponse(saved.getId(), now);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RouteAlertScheduleResponse> getActive(long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = requireUser(userId);
        List<RouteAlertSchedule> schedules = scheduleRepository
                .findByUserAndStatusAndDeletedAtIsNull(user, RouteAlertScheduleStatus.ACTIVE);
        if (schedules.isEmpty()) {
            return List.of();
        }
        // Q3: schedule 별 in-flight occurrence 를 batch 로(N+1 방지).
        List<Long> ids = schedules.stream().map(RouteAlertSchedule::getId).toList();
        Map<Long, RouteArrivalAlert> inFlight = alertRepository.findActiveOccurrencesByScheduleIds(ids).stream()
                .collect(Collectors.toMap(RouteArrivalAlert::getScheduleId, Function.identity(), (a, b) -> a));
        return schedules.stream()
                .map(s -> RouteAlertScheduleResponse.from(s, nextArrivalAt(s, now), inFlight.get(s.getId())))
                .toList();
    }

    @Override
    @Transactional
    public void cancel(long userId, Long scheduleId) {
        User user = requireUser(userId);
        RouteAlertSchedule schedule = scheduleRepository.findByIdAndUserAndDeletedAtIsNull(scheduleId, user)
                .orElseThrow(() -> new NoSuchElementException("구독을 찾을 수 없습니다."));
        schedule.markCanceled();
        // in-flight occurrence 취소 전파(기존 v2 cancel 재사용): occurrence CANCELED + PENDING notification CANCELED.
        for (RouteArrivalAlert occ : alertRepository
                .findByScheduleIdAndStatusAndDeletedAtIsNull(scheduleId, RouteAlertStatus.ACTIVE)) {
            occ.markCanceled();
            notificationRepository.cancelPendingByAlertId(occ.getId());
        }
    }

    /** create 응답: 최신 schedule + in-flight occurrence + 계산된 다음 도래시각. */
    private RouteAlertScheduleResponse buildResponse(Long scheduleId, LocalDateTime now) {
        RouteAlertSchedule s = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new NoSuchElementException("구독을 찾을 수 없습니다."));
        RouteArrivalAlert occ = alertRepository
                .findByScheduleIdAndStatusAndDeletedAtIsNull(scheduleId, RouteAlertStatus.ACTIVE).stream()
                .findFirst().orElse(null);
        return RouteAlertScheduleResponse.from(s, nextArrivalAt(s, now), occ);
    }

    private LocalDateTime nextArrivalAt(RouteAlertSchedule s, LocalDateTime now) {
        return RouteWeekdays.nextOccurrence(now, s.getArrivalTime(), s.getWeekdaysMask(), s.isRepeat());
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }
}
