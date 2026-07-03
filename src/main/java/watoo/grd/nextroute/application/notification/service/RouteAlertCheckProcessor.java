package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertPathSnapshot;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.TravelTimeQuery;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.RealtimeArrivalCalculator;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * 체크포인트별 단건 처리(first check / final check / 만료). 각 alert 를 독립 트랜잭션·낙관락으로 처리해
 * 발송 디스패처(PR-C)와의 동시성 정합을 지킨다. CHANGE 는 final check 에서만 발행한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RouteAlertCheckProcessor {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final RouteArrivalAlertRepository alertRepository;
    private final RouteArrivalAlertNotificationRepository notificationRepository;
    private final RouteTravelTimeEstimator estimator;
    private final SearchTimeBusQueryPort busPort;
    private final RouteServiceWindowChecker serviceWindowChecker;
    private final RouteArrivalAlertProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Transactional
    public void process(long alertId) {
        RouteArrivalAlert alert = alertRepository.findById(alertId).orElse(null);
        if (alert == null || !alert.isActive()) return;

        LocalDateTime now = LocalDateTime.now(clock);
        if (alert.getExpiresAt().isBefore(now)) {
            alert.markExpired();
            notificationRepository.cancelPendingByAlertId(alertId);
            return;
        }

        switch (alert.getPhase()) {
            case WAITING_FIRST_CHECK -> firstCheck(alert, now);
            case WAITING_FINAL_CHECK -> finalCheck(alert, now);
            default -> { /* WAITING_DEPARTURE(스캔 제외)·COMPLETED — no-op */ }
        }
    }

    // ── first check ───────────────────────────────────────────────────────────

    private void firstCheck(RouteArrivalAlert alert, LocalDateTime now) {
        int userLead = alert.getUserLeadMinutes();
        int buffer = alert.getBufferMinutes();

        RouteEvaluation eval;
        try {
            eval = estimator.evaluate(query(alert), EvaluationMode.REALTIME);
        } catch (IllegalStateException e) {
            // 경로없음/운행불가 → baseline 유지 + LOW, 스냅샷 없음(final check 자동 생략). DEPARTURE 는 발송.
            LocalDateTime leaveBy = alert.getBaselineLeaveByAt();
            LocalDateTime deptAt = leaveBy.minusMinutes(userLead);
            createDeparture(alert, deptAt, leaveBy, now);
            alert.applyFirstCheck(alert.getBaselineDurationSec(), leaveBy, deptAt, RouteAlertReliability.LOW, null);
            log.debug("[RouteAlertCheck] first check fallback(baseline) alert={} — {}", alert.getId(), e.getMessage());
            return;
        }

        LocalDateTime leaveBy = alert.getTargetArrivalAt().minusSeconds(eval.durationSec()).minusMinutes(buffer);
        LocalDateTime deptAt = leaveBy.minusMinutes(userLead);
        String snapshot = writeSnapshot(eval);

        createDeparture(alert, deptAt, leaveBy, now);
        alert.applyFirstCheck(eval.durationSec(), leaveBy, deptAt, eval.reliability(), snapshot);
    }

    private void createDeparture(RouteArrivalAlert alert, LocalDateTime deptAt,
                                 LocalDateTime leaveBy, LocalDateTime now) {
        LocalDateTime scheduledAt = deptAt.isBefore(now) ? now : deptAt;
        notificationRepository.save(RouteArrivalAlertNotification.builder()
                .alert(alert)
                .type(RouteNotificationType.DEPARTURE)
                .dedupKey(alert.getId() + ":DEPARTURE")
                .scheduledAt(scheduledAt)
                .targetLeaveByAt(leaveBy)
                .payload(null)
                .build());
    }

    // ── final check ─────────────────────────────────────────────────────────────

    private void finalCheck(RouteArrivalAlert alert, LocalDateTime now) {
        RouteAlertPathSnapshot snap = readSnapshot(alert.getSelectedPathSnapshot());
        if (snap == null || snap.boardings() == null || snap.boardings().isEmpty()) {
            alert.applyFinalCheck(now, alert.getReliability());
            return;
        }

        LocalDateTime departAt = alert.getLastNotifiedLeaveByAt() != null
                ? alert.getLastNotifiedLeaveByAt()
                : alert.getRecommendedLeaveByAt();
        Instant departInstant = departAt.atZone(SEOUL).toInstant();

        int cumWaitSec = 0;
        int degraded = 0;
        boolean anyDown = false;

        for (RouteAlertPathSnapshot.Boarding b : snap.boardings()) {
            Instant arriveAtStop = departInstant.plusSeconds((long) b.offsetSec() + cumWaitSec);
            LocalDateTime arriveLdt = LocalDateTime.ofInstant(arriveAtStop, SEOUL);

            if (serviceWindowChecker.judge(b.routeId(), b.stopId(), arriveLdt)
                    == RouteServiceWindowChecker.Availability.OUT_OF_SERVICE) {
                degraded++;
            }

            int waitSec;
            BusQueryResult<BusArrivalInfo> r = busPort.getArrInfoByStop(b.stopId(), b.routeId(), b.seq());
            if (r.isOk() && !r.data().isEmpty()) {
                BusArrivalInfo ai = r.data().stream()
                        .filter(a -> b.routeId().equals(a.routeId())).findFirst().orElse(null);
                if (ai == null) {                       // 응답에 해당 route 없음 → STALE
                    degraded++;
                    anyDown = true;
                    waitSec = fallbackWait(b);
                } else {
                    Optional<Instant> arr = RealtimeArrivalCalculator.earliest(ai, arriveAtStop);
                    if (arr.isPresent()) {
                        waitSec = (int) (arr.get().getEpochSecond() - arriveAtStop.getEpochSecond());
                    } else {                            // 다가오는 버스 없음 → term/2
                        anyDown = true;
                        waitSec = fallbackWait(b);
                    }
                }
            } else {                                    // 조회 실패 → term/2
                anyDown = true;
                waitSec = fallbackWait(b);
            }
            cumWaitSec += Math.max(0, waitSec);
        }

        int finalDurationSec = snap.fixedSec() + cumWaitSec;
        LocalDateTime finalLeaveBy = alert.getTargetArrivalAt()
                .minusSeconds(finalDurationSec)
                .minusMinutes(alert.getBufferMinutes());

        double staleRatio = (double) degraded / snap.boardings().size();
        if (staleRatio > properties.getSnapshotStaleThreshold()) {
            // 재검증 신뢰 불가 → CHANGE 억제, 기존 추천 유지.
            alert.applyFinalCheck(now, RouteAlertReliability.LOW);
            return;
        }

        RouteAlertReliability reliability = anyDown || degraded > 0
                ? RouteAlertReliability.LOW : RouteAlertReliability.MEDIUM;

        // CHANGE 판정은 clamp 전 원값. 표시값만 과거→now clamp.
        LocalDateTime base = alert.getLastNotifiedLeaveByAt();
        if (base != null && alert.getChangeAlertCount() == 0) {
            long diffMin = Math.abs(Duration.between(base, finalLeaveBy).toMinutes());
            if (diffMin >= properties.getChangeThresholdMinutes()) {
                boolean urgent = finalLeaveBy.isBefore(now);
                LocalDateTime display = urgent ? now : finalLeaveBy;
                notificationRepository.save(RouteArrivalAlertNotification.builder()
                        .alert(alert)
                        .type(RouteNotificationType.CHANGE)
                        .dedupKey(alert.getId() + ":CHANGE")
                        .scheduledAt(now)
                        .targetLeaveByAt(display)
                        .payload(urgent ? "{\"urgent\":true}" : null)
                        .build());
                alert.incrementChangeAlertCount();
            }
        }

        alert.applyFinalCheck(now, reliability);
    }

    private int fallbackWait(RouteAlertPathSnapshot.Boarding b) {
        return b.headwayMinutes() * 60 / 2;
    }

    // ── helper ─────────────────────────────────────────────────────────────────

    private TravelTimeQuery query(RouteArrivalAlert a) {
        return new TravelTimeQuery(a.getStartX(), a.getStartY(), a.getStartName(),
                a.getEndX(), a.getEndY(), a.getEndName(), a.getTargetArrivalAt());
    }

    private String writeSnapshot(RouteEvaluation eval) {
        if (eval.selectedPath() == null) return null;
        try {
            return objectMapper.writeValueAsString(RouteAlertPathSnapshot.from(eval.selectedPath()));
        } catch (Exception e) {
            log.warn("[RouteAlertCheck] snapshot 직렬화 실패: {}", e.getMessage());
            return null;
        }
    }

    private RouteAlertPathSnapshot readSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, RouteAlertPathSnapshot.class);
        } catch (Exception e) {
            log.warn("[RouteAlertCheck] snapshot 역직렬화 실패: {}", e.getMessage());
            return null;
        }
    }
}
