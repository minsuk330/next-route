package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.port.out.RouteOutOfServiceException;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertPhase;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
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
            set(a, "id", 55L);
            return a;
        });
    }

    private RouteEvaluation eval(int durationSec, Integer maxHeadway) {
        return new RouteEvaluation(durationSec, RouteAlertReliability.MEDIUM, null, maxHeadway);
    }

    @Test
    void create_computesBaselineAndFirstCheck_noDepartureRow() {
        stubUserAndSave();
        // 목표 = now+120분, 소요 30분(1800s), 버퍼 5분 → baselineLeaveBy = now+85분
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(1800, 12));
        LocalDateTime target = now().plusMinutes(120);

        service().create(7L, req(target, 10, 5));

        ArgumentCaptor<RouteArrivalAlert> cap = ArgumentCaptor.forClass(RouteArrivalAlert.class);
        verify(alertRepository).save(cap.capture());
        RouteArrivalAlert alert = cap.getValue();
        assertThat(alert.getBaselineDurationSec()).isEqualTo(1800);
        assertThat(alert.getBaselineLeaveByAt()).isEqualTo(target.minusMinutes(35));
        // safetyMargin = 3 + min(round(12*0.5)=6, 20) = 9 → firstCheck = baselineLeaveBy - lead(10) - 9
        assertThat(alert.getFirstCheckAt()).isEqualTo(target.minusMinutes(35 + 10 + 9));
        assertThat(alert.getPhase()).isEqualTo(RouteAlertPhase.WAITING_FIRST_CHECK);
        assertThat(alert.getNextActionAt()).isEqualTo(alert.getFirstCheckAt());
        // DEPARTURE row 는 등록 시 미생성
        verify(notificationRepository, never()).save(any());
    }

    @Test
    void create_noBusHeadway_usesBaseMarginOnly() {
        stubUserAndSave();
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(600, null)); // 10분, 버스 없음
        LocalDateTime target = now().plusMinutes(120);

        service().create(7L, req(target, 5, 5)); // baselineLeaveBy = target-15분

        ArgumentCaptor<RouteArrivalAlert> cap = ArgumentCaptor.forClass(RouteArrivalAlert.class);
        verify(alertRepository).save(cap.capture());
        // safetyMargin = 3 (headway 없음) → firstCheck = target-15 - lead(5) - 3
        assertThat(cap.getValue().getFirstCheckAt()).isEqualTo(target.minusMinutes(15 + 5 + 3));
    }

    @Test
    void create_firstCheckInPast_clampsToNow() {
        stubUserAndSave();
        // 목표 = now+20분, 소요 10분 → baselineLeaveBy = now+5분, firstCheck 은 과거 → now clamp
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(600, 10));
        LocalDateTime target = now().plusMinutes(20);

        service().create(7L, req(target, 5, 5));

        ArgumentCaptor<RouteArrivalAlert> cap = ArgumentCaptor.forClass(RouteArrivalAlert.class);
        verify(alertRepository).save(cap.capture());
        assertThat(cap.getValue().getFirstCheckAt()).isEqualTo(now());
    }

    @Test
    void create_baselineLeaveByAlreadyPast_throws422() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        // 목표 = now+10분, 소요 20분 → baselineLeaveBy = now-15분(과거) → IllegalStateException(422)
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(1200, 10));

        assertThatThrownBy(() -> service().create(7L, req(now().plusMinutes(10), 5, 5)))
                .isInstanceOf(IllegalStateException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_userLeadNotAllowed_throws() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));

        assertThatThrownBy(() -> service().create(7L, req(now().plusMinutes(60), 7, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(estimator, never()).evaluate(any(), any());
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
    void create_outOfService_propagates422() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC)))
                .willThrow(new RouteOutOfServiceException("운행불가"));

        assertThatThrownBy(() -> service().create(7L, req(now().plusMinutes(120), 5, 5)))
                .isInstanceOf(IllegalStateException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void cancel_marksCanceledAndPropagates() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        RouteArrivalAlert alert = RouteArrivalAlert.builder()
                .user(user).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineDurationSec(600).baselineLeaveByAt(now().plusMinutes(45))
                .firstCheckAt(now().plusMinutes(30)).reliability(RouteAlertReliability.MEDIUM)
                .expiresAt(now().plusMinutes(90)).build();
        set(alert, "id", 55L);
        given(alertRepository.findByIdAndUserAndDeletedAtIsNull(eq(55L), any()))
                .willReturn(Optional.of(alert));

        service().cancel(7L, 55L);

        assertThat(alert.isActive()).isFalse();
        assertThat(alert.getNextActionAt()).isNull();
        verify(notificationRepository).cancelPendingByAlertId(55L);
    }

    @Test
    void getActive_filtersActive() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        RouteArrivalAlert active = RouteArrivalAlert.builder()
                .user(user).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineLeaveByAt(now().plusMinutes(45)).firstCheckAt(now().plusMinutes(30))
                .expiresAt(now().plusMinutes(90)).build();
        RouteArrivalAlert canceled = RouteArrivalAlert.builder()
                .user(user).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineLeaveByAt(now().plusMinutes(45)).firstCheckAt(now().plusMinutes(30))
                .expiresAt(now().plusMinutes(90)).build();
        canceled.markCanceled();
        given(alertRepository.findByUserAndDeletedAtIsNull(any())).willReturn(List.of(active, canceled));

        List<?> result = service().getActive(7L);

        assertThat(result).hasSize(1);
    }
}
