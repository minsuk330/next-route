package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.application.notification.dto.RouteAlertUpdateRequest;
import watoo.grd.nextroute.application.notification.exception.ScheduleNotEditableException;
import watoo.grd.nextroute.application.notification.port.in.CancelRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetRouteAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.UpdateRouteAlertUseCase;
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
import watoo.grd.nextroute.application.notification.exception.TossIdentityRequiredException;

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
        implements CreateRouteAlertUseCase, UpdateRouteAlertUseCase,
        CancelRouteAlertUseCase, GetRouteAlertUseCase {

    private static final Set<Integer> ALLOWED_LEAD = Set.of(0, 5, 10, 15);

    private final RouteAlertScheduleRepository scheduleRepository;
    private final RouteArrivalAlertRepository alertRepository;
    private final RouteArrivalAlertNotificationRepository notificationRepository;
    private final RouteAlertScheduleSpawnService spawnService;
    private final UserRepository userRepository;
    private final RouteArrivalAlertProperties properties;
    private final PlatformTransactionManager transactionManager;
    private final Clock clock;

    @Override
    public RouteAlertScheduleResponse create(long userId, RouteAlertRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);

        int userLead = request.getUserLeadMinutes();
        int mask = validateAndMask(userLead, request.getArrivalTime(), request.isRepeat(), request.getWeekdays());
        int buffer = resolveBuffer(request.getBufferMinutes());

        User user = requireTossUser(userId);

        RouteAlertSchedule entity = RouteAlertSchedule.builder()
                .user(user)
                .startX(request.getStartX()).startY(request.getStartY()).startName(request.getStartName())
                .endX(request.getEndX()).endY(request.getEndY()).endName(request.getEndName())
                .arrivalTime(request.getArrivalTime())
                .weekdaysMask(mask)
                .repeat(request.isRepeat())
                .userLeadMinutes(userLead)
                .bufferMinutes(buffer)
                .build();

        // tx1: schedule 저장을 명시적 트랜잭션으로 커밋. spawn(tx2) 실패가 이 저장을 rollback 하지 않도록
        // create() 전체를 하나의 tx 로 묶지 않고, 저장만 독립 tx 로 확정한다.
        RouteAlertSchedule saved = new TransactionTemplate(transactionManager)
                .execute(status -> scheduleRepository.save(entity));

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
    public RouteAlertScheduleResponse update(long userId, Long scheduleId, RouteAlertUpdateRequest request) {
        LocalDateTime now = LocalDateTime.now(clock);
        int userLead = request.getUserLeadMinutes();
        boolean repeat = request.isRepeat();
        int mask = validateAndMask(userLead, request.getArrivalTime(), repeat, request.getWeekdays());
        int buffer = resolveBuffer(request.getBufferMinutes());
        User user = requireUser(userId);

        // tx1: schedule 갱신 + in-flight occurrence 재처리(supersede/DONE). spawn(tx2) 과 분리해 격리.
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                RouteAlertSchedule schedule = scheduleRepository
                        .findByIdAndUserAndDeletedAtIsNull(scheduleId, user)
                        .orElseThrow(() -> new NoSuchElementException("구독을 찾을 수 없습니다."));
                if (!schedule.isActive()) {
                    throw new ScheduleNotEditableException("ACTIVE 상태만 수정할 수 있습니다: " + schedule.getStatus());
                }
                // 필드 갱신 + 백오프 메타 리셋(안 하면 tx2 재spawn 이 백오프 게이트에 막혀 skip).
                schedule.update(request.getArrivalTime(), mask, repeat, userLead, buffer);

                // occurrence entity 변경을 먼저 모두 수행하고, notification 취소(cancelPendingByAlertId)는 나중에 일괄.
                // cancelPendingByAlertId 는 @Modifying(clearAutomatically=true) 라 호출 시 영속성 컨텍스트를 비운다 —
                // 취소를 각 반복에서 섞으면 첫 clear 이후 나머지 occurrence 가 detached 되어 변경이 유실된다.
                List<Long> toCancelNotif = new java.util.ArrayList<>();
                // ACTIVE occurrence 전체 순회(DB 가 active 1개 보장 안 함).
                for (RouteArrivalAlert occ : alertRepository
                        .findByScheduleIdAndStatusAndDeletedAtIsNull(scheduleId, RouteAlertStatus.ACTIVE)) {
                    if (occ.getLastNotifiedAt() == null) {          // 발송 전 → 폐기
                        occ.markSuperseded();
                        toCancelNotif.add(occ.getId());
                    } else if (!repeat) {                            // 발송 후 one-time → 조기 종결
                        occ.markDoneOnEdit();
                        toCancelNotif.add(occ.getId());
                    }
                    // 발송 후 repeat=true → 유지(다음 주부터 새 설정)
                }
                // one-time C2: 이미 발송·DONE 종결됐으나 schedule 이 아직 ACTIVE 인 윈도우 —
                // 그 DONE occurrence 도 soft-delete 해 유니크 슬롯 해제 + 재생성 보장(같은 target 대비).
                // (findTop 은 위 soft-delete 를 autoflush 로 반영해 방금 폐기한 건 제외한다.)
                if (!repeat) {
                    alertRepository.findTopByScheduleIdAndDeletedAtIsNullOrderByIdDesc(scheduleId)
                            .filter(o -> o.getStatus() == RouteAlertStatus.DONE)
                            .ifPresent(o -> {
                                o.markDoneOnEdit();
                                toCancelNotif.add(o.getId());
                            });
                }
                // 일괄 취소: 첫 호출의 flushAutomatically 가 clear 전에 위 entity 변경을 모두 flush 한다.
                for (Long alertId : toCancelNotif) {
                    notificationRepository.cancelPendingByAlertId(alertId);
                }
            });
        } catch (OptimisticLockingFailureException e) {
            throw new ScheduleNotEditableException("동시 수정 충돌이 발생했습니다. 다시 시도해 주세요.");
        }

        // tx2: 새 파라미터로 재 materialize(발송후 repeat=true 는 in-flight 남아 가드 skip).
        try {
            spawnService.processScheduleSpawn(scheduleId, now);
        } catch (RuntimeException e) {
            log.warn("[RouteAlert] 수정 후 재 materialize 실패 scheduleId={}: {}", scheduleId, e.getMessage());
        }

        return buildResponse(scheduleId, now);
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

    /** 생성/수정 공통 검증 + weekdays → mask. */
    private int validateAndMask(int userLead, LocalTime arrivalTime, boolean repeat, List<String> weekdays) {
        if (!ALLOWED_LEAD.contains(userLead)) {
            throw new IllegalArgumentException("userLeadMinutes 는 {0,5,10,15} 중 하나여야 합니다: " + userLead);
        }
        if (arrivalTime == null) {
            throw new IllegalArgumentException("arrivalTime 이 필요합니다.");
        }
        int mask = RouteWeekdays.maskFromCodes(weekdays);
        if (repeat && mask == 0) {
            throw new IllegalArgumentException("반복(repeat=true) 은 weekdays 를 1개 이상 지정해야 합니다.");
        }
        return mask;
    }

    private int resolveBuffer(Integer requested) {
        return requested != null ? requested : properties.getDefaultBufferMinutes();
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }

    /**
     * 알림 구독은 토스 신원이 있는 사용자만 만들 수 있다.
     *
     * <p>발송 채널이 토스뿐이라 없으면 받을 수단이 없다. 만드는 시점에 막지 않으면 요청은
     * 성공하고 백그라운드 발송만 조용히 실패해, 사용자는 알림이 온다고 믿게 된다.
     */
    private User requireTossUser(long userId) {
        User user = requireUser(userId);
        if (!user.hasTossIdentity()) {
            throw new TossIdentityRequiredException();
        }
        return user;
    }
}
