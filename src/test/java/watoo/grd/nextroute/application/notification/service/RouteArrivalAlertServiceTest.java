package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlertNotification;
import watoo.grd.nextroute.domain.notification.entity.RouteNotificationType;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteArrivalAlertServiceTest {

    @Mock RouteArrivalAlertRepository alertRepository;
    @Mock RouteArrivalAlertNotificationRepository notificationRepository;
    @Mock RouteTravelTimeEstimator estimator;
    @Mock UserRepository userRepository;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();
    // 고정 now = 2026-06-23 09:00 KST
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    RouteArrivalAlertService service() {
        return new RouteArrivalAlertService(
                alertRepository, notificationRepository, estimator, userRepository, properties, clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private RouteAlertRequest req(LocalDateTime target, int userLead, Integer buffer) {
        RouteAlertRequest r = new RouteAlertRequest();
        set(r, "startX", 127.0);
        set(r, "startY", 37.5);
        set(r, "endX", 127.1);
        set(r, "endY", 37.6);
        set(r, "targetArrivalAt", target);
        set(r, "userLeadMinutes", userLead);
        set(r, "bufferMinutes", buffer);
        return r;
    }

    private static void set(Object t, String f, Object v) {
        try {
            Class<?> c = t.getClass();
            Field fld = null;
            while (c != null && fld == null) {
                try { fld = c.getDeclaredField(f); } catch (NoSuchFieldException ignore) { c = c.getSuperclass(); }
            }
            if (fld == null) throw new NoSuchFieldException(f);
            fld.setAccessible(true);
            fld.set(t, v);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private void stubUserAndSave() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        given(alertRepository.save(any())).willAnswer(inv -> {
            RouteArrivalAlert a = inv.getArgument(0);
            set(a, "id", 55L); // 식별자 생성 모사(dedupKey 용)
            return a;
        });
    }

    @Test
    void create_computesLeaveByAndCreatesDepartReminder() {
        stubUserAndSave();
        // 목표 = now + 120분, 소요 30분(1800s), 버퍼 5분 → leaveBy = target-35분 = now+85분
        given(estimator.estimateSeconds(any())).willReturn(1800);
        LocalDateTime target = now().plusMinutes(120);

        service().create(7L, req(target, 10, 5));

        ArgumentCaptor<RouteArrivalAlert> alertCap = ArgumentCaptor.forClass(RouteArrivalAlert.class);
        verify(alertRepository).save(alertCap.capture());
        RouteArrivalAlert alert = alertCap.getValue();
        assertThat(alert.getLeaveByAt()).isEqualTo(target.minusMinutes(35));
        assertThat(alert.getLastTotalDurationSec()).isEqualTo(1800);

        ArgumentCaptor<RouteArrivalAlertNotification> notiCap =
                ArgumentCaptor.forClass(RouteArrivalAlertNotification.class);
        verify(notificationRepository).save(notiCap.capture());
        RouteArrivalAlertNotification noti = notiCap.getValue();
        assertThat(noti.getType()).isEqualTo(RouteNotificationType.DEPART_REMINDER);
        assertThat(noti.getDedupKey()).isEqualTo("55:DEPART");
        // scheduledAt = leaveBy - userLead(10) = target-45분
        assertThat(noti.getScheduledAt()).isEqualTo(target.minusMinutes(45));
        assertThat(noti.getTargetLeaveByAt()).isEqualTo(target.minusMinutes(35));
    }

    @Test
    void create_remindTimeAlreadyPast_schedulesImmediately() {
        stubUserAndSave();
        // 목표 = now + 20분, 소요 30분 → leaveBy = now-15분(과거), 리마인드는 더 과거 → scheduledAt=now
        given(estimator.estimateSeconds(any())).willReturn(1800);
        LocalDateTime target = now().plusMinutes(20);

        service().create(7L, req(target, 10, 5));

        ArgumentCaptor<RouteArrivalAlertNotification> notiCap =
                ArgumentCaptor.forClass(RouteArrivalAlertNotification.class);
        verify(notificationRepository).save(notiCap.capture());
        assertThat(notiCap.getValue().getScheduledAt()).isEqualTo(now());
    }

    @Test
    void create_defaultBufferWhenNull() {
        stubUserAndSave();
        given(estimator.estimateSeconds(any())).willReturn(600); // 10분
        LocalDateTime target = now().plusMinutes(120);

        service().create(7L, req(target, 5, null)); // buffer null → 기본 5분

        ArgumentCaptor<RouteArrivalAlert> cap = ArgumentCaptor.forClass(RouteArrivalAlert.class);
        verify(alertRepository).save(cap.capture());
        assertThat(cap.getValue().getBufferMinutes()).isEqualTo(properties.getDefaultBufferMinutes());
        // leaveBy = target - 10분 - 5분
        assertThat(cap.getValue().getLeaveByAt()).isEqualTo(target.minusMinutes(15));
    }

    @Test
    void create_userLeadNotMultipleOf5_throws() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));

        assertThatThrownBy(() -> service().create(7L, req(now().plusMinutes(60), 7, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_targetInPast_throws() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));

        assertThatThrownBy(() -> service().create(7L, req(now().minusMinutes(1), 5, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void cancel_marksCanceledAndPropagatesToPendingNotifications() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        RouteArrivalAlert alert = RouteArrivalAlert.builder()
                .user(user).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .lastTotalDurationSec(600).leaveByAt(now().plusMinutes(45)).lastResearchAt(now())
                .expiresAt(now().plusMinutes(90)).build();
        set(alert, "id", 55L);
        given(alertRepository.findByIdAndUserAndDeletedAtIsNull(eq(55L), any()))
                .willReturn(Optional.of(alert));

        service().cancel(7L, 55L);

        assertThat(alert.isActive()).isFalse();
        verify(notificationRepository).cancelPendingByAlertId(55L);
    }
}
