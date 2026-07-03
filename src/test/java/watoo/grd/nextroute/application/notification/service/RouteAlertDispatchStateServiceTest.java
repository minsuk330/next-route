package watoo.grd.nextroute.application.notification.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertPathSnapshot;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.FinalCheckEligibility;
import watoo.grd.nextroute.application.notification.service.RouteAlertDispatchStateService.DispatchTask;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class RouteAlertDispatchStateServiceTest {

    @Mock RouteArrivalAlertNotificationRepository notificationRepository;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();
    ObjectMapper objectMapper = new ObjectMapper();
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    RouteAlertDispatchStateService service() {
        return new RouteAlertDispatchStateService(notificationRepository, properties, objectMapper, clock);
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

    private String snapshot(FinalCheckEligibility elig) {
        try {
            List<RouteAlertPathSnapshot.Boarding> bs = elig == FinalCheckEligibility.NO_BUS
                    ? List.of()
                    : List.of(new RouteAlertPathSnapshot.Boarding("s", "r", 3, 600, 12));
            return objectMapper.writeValueAsString(new RouteAlertPathSnapshot(1200, elig, bs));
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    /** WAITING_DEPARTURE alert (first check 완료 상태). */
    private RouteArrivalAlert waitingDeparture(int userLead, FinalCheckEligibility elig) {
        LocalDateTime target = now().plusMinutes(120);
        RouteArrivalAlert a = RouteArrivalAlert.builder()
                .user(new User(999L)).startX(1.0).startY(2.0).startName("집").endX(3.0).endY(4.0).endName("회사")
                .targetArrivalAt(target).userLeadMinutes(userLead).bufferMinutes(5)
                .baselineDurationSec(1800).baselineLeaveByAt(target.minusMinutes(35))
                .firstCheckAt(now()).reliability(RouteAlertReliability.MEDIUM)
                .expiresAt(target.plusMinutes(30)).build();
        set(a, "id", 55L);
        a.applyFirstCheck(1800, target.minusMinutes(35), target.minusMinutes(35 + userLead),
                RouteAlertReliability.MEDIUM, snapshot(elig));
        return a;
    }

    private RouteArrivalAlertNotification noti(RouteArrivalAlert alert, RouteNotificationType type, String payload) {
        RouteArrivalAlertNotification n = RouteArrivalAlertNotification.builder()
                .alert(alert).type(type).dedupKey("55:" + type)
                .scheduledAt(now()).targetLeaveByAt(alert.getRecommendedLeaveByAt() != null
                        ? alert.getRecommendedLeaveByAt() : alert.getBaselineLeaveByAt())
                .payload(payload).build();
        set(n, "id", 7L);
        return n;
    }

    // ── claim / stale 가드 ────────────────────────────────────────────────────

    @Test
    void claim_departureActive_processing() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        Optional<DispatchTask> task = service().claimForDispatch(7L);

        assertThat(task).isPresent();
        assertThat(task.get().type()).isEqualTo(RouteNotificationType.DEPARTURE);
        assertThat(task.get().tossUserKey()).isEqualTo(999L);
        assertThat(task.get().context()).containsKeys("leaveByAt", "targetArrivalAt", "startName", "endName");
        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.PROCESSING);
    }

    @Test
    void claim_parentCanceled_cancelsNotification() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        a.markCanceled();
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        assertThat(service().claimForDispatch(7L)).isEmpty();
        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.CANCELED);
    }

    @Test
    void claim_departureWrongPhase_cancels() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        a.recordNotified(a.getRecommendedLeaveByAt(), now(), now().plusMinutes(1)); // → WAITING_FINAL_CHECK
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        assertThat(service().claimForDispatch(7L)).isEmpty();
        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.CANCELED);
    }

    @Test
    void claim_notPending_empty() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        n.markProcessing(now()); // 이미 PROCESSING
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        assertThat(service().claimForDispatch(7L)).isEmpty();
    }

    // ── 발송성공 전이 ─────────────────────────────────────────────────────────

    @Test
    void markDepartureSent_userLeadHigh_transitionsToFinalCheck() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markDepartureSent(7L);

        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.SENT);
        assertThat(a.getPhase()).isEqualTo(RouteAlertPhase.WAITING_FINAL_CHECK);
        // finalCheckAt = recommendedLeaveBy - finalCheckLead(5) = target-40분
        assertThat(a.getNextActionAt()).isEqualTo(a.getTargetArrivalAt().minusMinutes(40));
        assertThat(a.getLastNotifiedLeaveByAt()).isEqualTo(a.getRecommendedLeaveByAt());
    }

    @Test
    void markDepartureSent_userLeadLow_done() {
        RouteArrivalAlert a = waitingDeparture(5, FinalCheckEligibility.ELIGIBLE); // lead 5 ≤ 5 → 생략
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markDepartureSent(7L);

        assertThat(a.getPhase()).isEqualTo(RouteAlertPhase.COMPLETED);
        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void markDepartureSent_noBusEligibility_done() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.NO_BUS);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markDepartureSent(7L);

        assertThat(a.getStatus()).isEqualTo(RouteAlertStatus.DONE);
    }

    @Test
    void markChangeSent_sentOnly() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.CHANGE, "{\"urgent\":true}");
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markChangeSent(7L);

        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.SENT);
    }

    // ── 실패 ───────────────────────────────────────────────────────────────────

    @Test
    void transientFailure_underMax_releaseToPendingWithBackoff() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        n.markProcessing(now()); // attemptCount=1
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markTransientFailure(7L, "timeout");

        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.PENDING);
        assertThat(n.getScheduledAt()).isEqualTo(now().plusSeconds(properties.getBackoffSeconds()));
    }

    @Test
    void transientFailure_atMax_failed() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        set(n, "attemptCount", properties.getMaxAttempts());
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markTransientFailure(7L, "timeout");

        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.FAILED);
    }

    @Test
    void permanentFailure_failed() {
        RouteArrivalAlert a = waitingDeparture(10, FinalCheckEligibility.ELIGIBLE);
        RouteArrivalAlertNotification n = noti(a, RouteNotificationType.DEPARTURE, null);
        given(notificationRepository.findById(7L)).willReturn(Optional.of(n));

        service().markPermanentFailure(7L, "bad-template");

        assertThat(n.getStatus()).isEqualTo(RouteNotificationStatus.FAILED);
    }
}
