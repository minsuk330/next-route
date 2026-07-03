package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertPathSnapshot;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.Boarding;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.FinalCheckEligibility;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.SelectedPath;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteAlertCheckProcessorTest {

    @Mock RouteArrivalAlertRepository alertRepository;
    @Mock RouteArrivalAlertNotificationRepository notificationRepository;
    @Mock RouteTravelTimeEstimator estimator;
    @Mock SearchTimeBusQueryPort busPort;
    @Mock RouteServiceWindowChecker serviceWindowChecker;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();
    ObjectMapper objectMapper = new ObjectMapper();
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    RouteAlertCheckProcessor processor() {
        return new RouteAlertCheckProcessor(alertRepository, notificationRepository, estimator,
                busPort, serviceWindowChecker, properties, objectMapper, clock);
    }

    LocalDateTime now() { return LocalDateTime.now(clock); }

    private static void set(Object t, String f, Object v) {
        try {
            Class<?> c = t.getClass();
            Field fld = null;
            while (c != null && fld == null) {
                try { fld = c.getDeclaredField(f); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
            }
            fld.setAccessible(true);
            fld.set(t, v);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private RouteArrivalAlert alert(LocalDateTime target, int userLead, int buffer) {
        RouteArrivalAlert a = RouteArrivalAlert.builder()
                .user(new User(1L)).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(target).userLeadMinutes(userLead).bufferMinutes(buffer)
                .baselineDurationSec(1800).baselineLeaveByAt(target.minusMinutes(35))
                .firstCheckAt(now()).reliability(RouteAlertReliability.MEDIUM)
                .expiresAt(target.plusMinutes(30)).build();
        set(a, "id", 55L);
        return a;
    }

    private String snapshotJson(int fixedSec, FinalCheckEligibility elig, RouteAlertPathSnapshot.Boarding... bs) {
        try {
            return objectMapper.writeValueAsString(new RouteAlertPathSnapshot(fixedSec, elig, List.of(bs)));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    // ── first check ───────────────────────────────────────────────────────────

    @Test
    void firstCheck_success_createsDepartureAndTransitions() {
        LocalDateTime target = now().plusMinutes(120);
        RouteArrivalAlert a = alert(target, 10, 5);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        SelectedPath sp = new SelectedPath(1200, List.of(new Boarding("stop1", "route1", 3, 600, 12)),
                FinalCheckEligibility.ELIGIBLE);
        given(estimator.evaluate(any(), eq(EvaluationMode.REALTIME)))
                .willReturn(new RouteEvaluation(1800, RouteAlertReliability.HIGH, sp, 12));

        processor().process(55L);

        ArgumentCaptor<RouteArrivalAlertNotification> cap =
                ArgumentCaptor.forClass(RouteArrivalAlertNotification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(RouteNotificationType.DEPARTURE);
        assertThat(cap.getValue().getDedupKey()).isEqualTo("55:DEPARTURE");
        assertThat(a.getPhase()).isEqualTo(RouteAlertPhase.WAITING_DEPARTURE);
        assertThat(a.getNextActionAt()).isNull();
        assertThat(a.getRecommendedLeaveByAt()).isEqualTo(target.minusMinutes(35));
        assertThat(a.getSelectedPathSnapshot()).isNotNull();
    }

    @Test
    void firstCheck_evalFails_baselineFallback() {
        LocalDateTime target = now().plusMinutes(120);
        RouteArrivalAlert a = alert(target, 10, 5);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        given(estimator.evaluate(any(), eq(EvaluationMode.REALTIME)))
                .willThrow(new IllegalStateException("경로없음"));

        processor().process(55L);

        verify(notificationRepository).save(any()); // baseline 으로 DEPARTURE 는 발송
        assertThat(a.getPhase()).isEqualTo(RouteAlertPhase.WAITING_DEPARTURE);
        assertThat(a.getReliability()).isEqualTo(RouteAlertReliability.LOW);
        assertThat(a.getSelectedPathSnapshot()).isNull();
        assertThat(a.getRecommendedLeaveByAt()).isEqualTo(a.getBaselineLeaveByAt());
    }

    // ── final check ─────────────────────────────────────────────────────────────

    private RouteArrivalAlert finalPhaseAlert(LocalDateTime target, int buffer,
                                              LocalDateTime lastNotified, String snapshot) {
        RouteArrivalAlert a = alert(target, 10, buffer);
        a.applyFirstCheck(1800, target.minusMinutes(35), target.minusMinutes(45),
                RouteAlertReliability.HIGH, snapshot);
        a.recordNotified(lastNotified, now(), now().plusMinutes(1)); // → WAITING_FINAL_CHECK
        return a;
    }

    @Test
    void finalCheck_noOdsayResearch_onlyTargetedArrival() {
        String snap = snapshotJson(1200, FinalCheckEligibility.ELIGIBLE,
                new RouteAlertPathSnapshot.Boarding("stop1", "route1", 3, 600, 12));
        RouteArrivalAlert a = finalPhaseAlert(now().plusMinutes(120), 5, now().plusMinutes(80), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        given(serviceWindowChecker.judge(any(), any(), any()))
                .willReturn(RouteServiceWindowChecker.Availability.AVAILABLE);
        given(busPort.getArrInfoByStop(any(), any(), anyInt())).willReturn(BusQueryResult.error());

        processor().process(55L);

        verify(estimator, never()).evaluate(any(), any());   // ODsay 재검색 없음
        verify(busPort).getArrInfoByStop(eq("stop1"), eq("route1"), eq(3));
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void finalCheck_bigDiff_createsChangeOnce() {
        // fixedSec 1200 + fallback wait(headway12/2=6분=360s) = 1560s → finalLeaveBy = target-26분-5분
        LocalDateTime target = now().plusMinutes(120);
        String snap = snapshotJson(1200, FinalCheckEligibility.ELIGIBLE,
                new RouteAlertPathSnapshot.Boarding("stop1", "route1", 3, 600, 12));
        // finalLeaveBy = target-31분 = now+89분. lastNotified = now+100분 → diff 11분 ≥ 5 → CHANGE
        RouteArrivalAlert a = finalPhaseAlert(target, 5, now().plusMinutes(100), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        given(serviceWindowChecker.judge(any(), any(), any()))
                .willReturn(RouteServiceWindowChecker.Availability.AVAILABLE);
        given(busPort.getArrInfoByStop(any(), any(), anyInt())).willReturn(BusQueryResult.error());

        processor().process(55L);

        ArgumentCaptor<RouteArrivalAlertNotification> cap =
                ArgumentCaptor.forClass(RouteArrivalAlertNotification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(RouteNotificationType.CHANGE);
        assertThat(a.getChangeAlertCount()).isEqualTo(1);
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void finalCheck_smallDiff_noChange() {
        LocalDateTime target = now().plusMinutes(120);
        String snap = snapshotJson(1200, FinalCheckEligibility.ELIGIBLE,
                new RouteAlertPathSnapshot.Boarding("stop1", "route1", 3, 600, 12));
        // finalLeaveBy = now+89분. lastNotified = now+90분 → diff 1분 < 5 → CHANGE 없음
        RouteArrivalAlert a = finalPhaseAlert(target, 5, now().plusMinutes(90), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        given(serviceWindowChecker.judge(any(), any(), any()))
                .willReturn(RouteServiceWindowChecker.Availability.AVAILABLE);
        given(busPort.getArrInfoByStop(any(), any(), anyInt())).willReturn(BusQueryResult.error());

        processor().process(55L);

        verify(notificationRepository, never()).save(any());
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void finalCheck_pastLeaveBy_clampsUrgent() {
        // target 가까워 finalLeaveBy 가 과거 → display=now, urgent
        LocalDateTime target = now().plusMinutes(20);
        String snap = snapshotJson(1200, FinalCheckEligibility.ELIGIBLE,
                new RouteAlertPathSnapshot.Boarding("stop1", "route1", 3, 600, 12));
        // finalLeaveBy = target-31분 = now-11분(과거). lastNotified = now+5분 → diff 16분 ≥ 5 → CHANGE
        RouteArrivalAlert a = finalPhaseAlert(target, 5, now().plusMinutes(5), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        given(serviceWindowChecker.judge(any(), any(), any()))
                .willReturn(RouteServiceWindowChecker.Availability.AVAILABLE);
        given(busPort.getArrInfoByStop(any(), any(), anyInt())).willReturn(BusQueryResult.error());

        processor().process(55L);

        ArgumentCaptor<RouteArrivalAlertNotification> cap =
                ArgumentCaptor.forClass(RouteArrivalAlertNotification.class);
        verify(notificationRepository).save(cap.capture());
        assertThat(cap.getValue().getType()).isEqualTo(RouteNotificationType.CHANGE);
        assertThat(cap.getValue().getTargetLeaveByAt()).isEqualTo(now()); // clamp
        assertThat(cap.getValue().getPayload()).contains("urgent");
    }

    @Test
    void finalCheck_staleRatioExceeded_suppressesChange() {
        LocalDateTime target = now().plusMinutes(120);
        String snap = snapshotJson(1200, FinalCheckEligibility.ELIGIBLE,
                new RouteAlertPathSnapshot.Boarding("stop1", "route1", 3, 600, 12));
        RouteArrivalAlert a = finalPhaseAlert(target, 5, now().plusMinutes(100), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));
        // 단일 boarding 이 OUT_OF_SERVICE → staleRatio 1.0 > 0.5 → CHANGE 억제
        given(serviceWindowChecker.judge(any(), any(), any()))
                .willReturn(RouteServiceWindowChecker.Availability.OUT_OF_SERVICE);
        given(busPort.getArrInfoByStop(any(), any(), anyInt())).willReturn(BusQueryResult.error());

        processor().process(55L);

        verify(notificationRepository, never()).save(any());
        assertThat(a.getReliability()).isEqualTo(RouteAlertReliability.LOW);
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void finalCheck_emptyBoardings_doneNoChange() {
        String snap = snapshotJson(1200, FinalCheckEligibility.NO_BUS); // boardings 없음
        RouteArrivalAlert a = finalPhaseAlert(now().plusMinutes(120), 5, now().plusMinutes(80), snap);
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));

        processor().process(55L);

        verify(busPort, never()).getArrInfoByStop(any(), any(), anyInt());
        verify(notificationRepository, never()).save(any());
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    // ── 만료 ────────────────────────────────────────────────────────────────────

    @Test
    void expired_marksExpiredAndPropagates() {
        RouteArrivalAlert a = alert(now().plusMinutes(120), 10, 5);
        set(a, "expiresAt", now().minusMinutes(1)); // 만료
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));

        processor().process(55L);

        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.EXPIRED);
        verify(notificationRepository).cancelPendingByAlertId(55L);
        verify(estimator, never()).evaluate(any(), any());
    }

    @Test
    void inactive_skipped() {
        RouteArrivalAlert a = alert(now().plusMinutes(120), 10, 5);
        a.markCanceled();
        given(alertRepository.findById(55L)).willReturn(Optional.of(a));

        processor().process(55L);

        verify(estimator, never()).evaluate(any(), any());
        verify(notificationRepository, never()).save(any());
    }
}
