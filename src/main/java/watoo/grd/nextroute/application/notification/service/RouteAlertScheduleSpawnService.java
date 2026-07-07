package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.port.out.RouteOutOfServiceException;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.TravelTimeQuery;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteWeekdays;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * schedule → occurrence materialize 의 트랜잭션 경계 소유(self-invocation 프록시 회피 위해 API 서비스와 분리).
 * 즉시생성(create) 경로와 spawner 스캔이 이 서비스의 {@link #processScheduleSpawn}을 공유한다.
 *
 * <p>materialize 실패 3분류(roll-forward 모델):
 * <ul>
 *   <li>too-late(baselineLeaveBy≤now)·운행불가 → roll-forward: 상태변경 없이 skip. nextOccurrence 자연 이월.</li>
 *   <li>시스템오류(경로없음/예외) → retry metadata 기록 + 지수 백오프, ACTIVE 유지.</li>
 *   <li>유니크 충돌 → 예외 전파(호출자가 idempotent 성공 취급). tx rollback.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteAlertScheduleSpawnService {

    private final RouteAlertScheduleRepository scheduleRepository;
    private final RouteArrivalAlertRepository alertRepository;
    private final RouteTravelTimeEstimator estimator;
    private final RouteArrivalAlertProperties properties;

    /** too-late(이미 출발했어야 함) roll-forward 신호. 내부 전용. */
    private static final class OccurrenceTooLateException extends RuntimeException {
    }

    /**
     * 단일 schedule spawn 처리(독립 트랜잭션). in-flight ≤ 1 유지 + 종결/재무장/이월 판정.
     * 유니크 충돌(DataIntegrityViolation)은 catch 하지 않고 전파 — 호출자가 멱등 skip 로 처리한다.
     */
    @Transactional
    public void processScheduleSpawn(Long scheduleId, LocalDateTime now) {
        RouteAlertSchedule s = scheduleRepository.findById(scheduleId).orElse(null);
        if (s == null || !s.isActive() || s.getDeletedAt() != null) {
            return;
        }
        // 백오프 게이트: 직전 시스템오류의 재시도 시각 전이면 대기.
        if (s.getNextMaterializationRetryAt() != null && now.isBefore(s.getNextMaterializationRetryAt())) {
            return;
        }
        // in-flight 있으면 진행 중 → skip.
        if (alertRepository.existsByScheduleIdAndStatusAndDeletedAtIsNull(scheduleId, RouteAlertStatus.ACTIVE)) {
            return;
        }
        // 일회성 종결: 마지막 occurrence 가 DONE(발송 성공) 이면 COMPLETED. EXPIRED 등은 이월(fall-through).
        if (!s.isRepeat() && s.getLastSpawnedTargetArrivalAt() != null) {
            RouteAlertStatus last = alertRepository.findTopByScheduleIdAndDeletedAtIsNullOrderByIdDesc(scheduleId)
                    .map(RouteArrivalAlert::getStatus).orElse(null);
            if (last == RouteAlertStatus.DONE) {
                s.markCompleted();
                return;
            }
        }
        LocalDateTime next = RouteWeekdays.nextOccurrence(now, s.getArrivalTime(), s.getWeekdaysMask(), s.isRepeat());
        // spawn window 밖(너무 이름) → 대기.
        if (Duration.between(now, next).compareTo(Duration.ofHours(properties.getSpawnLeadHours())) > 0) {
            return;
        }
        try {
            createOccurrence(s, next, now);
        } catch (OccurrenceTooLateException tooLate) {
            // roll-forward: 상태변경 없음. next 시각 지나면 nextOccurrence 가 다음 도래 반환.
            log.debug("[RouteAlertSpawn] too-late roll-forward scheduleId={} target={}", scheduleId, next);
        } catch (RouteOutOfServiceException oos) {
            // 운행불가(첫차전/막차후): roll-forward.
            log.debug("[RouteAlertSpawn] out-of-service roll-forward scheduleId={} target={}", scheduleId, next);
        } catch (IllegalStateException sys) {
            // 경로 자체 없음 등 시스템오류: retry metadata + 백오프.
            s.recordMaterializationError(sys.getMessage(), now,
                    properties.getSpawnBackoffBaseSeconds(), properties.getSpawnBackoffMaxSeconds());
            log.warn("[RouteAlertSpawn] system error scheduleId={} retry={} reason={}",
                    scheduleId, s.getMaterializationRetryCount(), sys.getMessage());
        }
    }

    /**
     * occurrence 1개 생성(STATIC baseline + firstCheck). 성공 시 alert 저장 + schedule.recordSpawn.
     *
     * @throws OccurrenceTooLateException baselineLeaveBy ≤ now(이미 출발했어야 함) → roll-forward
     * @throws RouteOutOfServiceException 후보 전부 운행불가 → roll-forward
     * @throws IllegalStateException      경로 자체 없음 → 시스템오류
     */
    private void createOccurrence(RouteAlertSchedule s, LocalDateTime target, LocalDateTime now) {
        int buffer = s.getBufferMinutes();
        RouteEvaluation eval = estimator.evaluate(new TravelTimeQuery(
                s.getStartX(), s.getStartY(), s.getStartName(),
                s.getEndX(), s.getEndY(), s.getEndName(),
                target, buffer), EvaluationMode.STATIC);

        LocalDateTime baselineLeaveByAt = target.minusSeconds(eval.durationSec()).minusMinutes(buffer);
        if (!baselineLeaveByAt.isAfter(now)) {
            throw new OccurrenceTooLateException();
        }

        int userLead = s.getUserLeadMinutes();
        int maxHeadway = eval.maxHeadwayMinutes() != null ? eval.maxHeadwayMinutes() : 0;
        int headwayMargin = Math.min(
                (int) Math.round(maxHeadway * properties.getFirstCheckHeadwayFactor()),
                properties.getFirstCheckMaxMarginMinutes());
        int safetyMargin = properties.getFirstCheckBaseMarginMinutes() + headwayMargin;
        LocalDateTime firstCheckAt = baselineLeaveByAt.minusMinutes(userLead).minusMinutes(safetyMargin);
        if (firstCheckAt.isBefore(now)) {
            firstCheckAt = now;
        }

        alertRepository.save(RouteArrivalAlert.builder()
                .user(s.getUser())
                .scheduleId(s.getId())
                .startX(s.getStartX()).startY(s.getStartY()).startName(s.getStartName())
                .endX(s.getEndX()).endY(s.getEndY()).endName(s.getEndName())
                .targetArrivalAt(target)
                .userLeadMinutes(userLead)
                .bufferMinutes(buffer)
                .baselineDurationSec(eval.durationSec())
                .baselineLeaveByAt(baselineLeaveByAt)
                .firstCheckAt(firstCheckAt)
                .reliability(eval.reliability())
                .expiresAt(target.plusMinutes(properties.getExpiresAfterTargetMinutes()))
                .build());
        s.recordSpawn(target);
    }
}
