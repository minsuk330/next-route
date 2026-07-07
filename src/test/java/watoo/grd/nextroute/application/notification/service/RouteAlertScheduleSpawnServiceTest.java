package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.port.out.RouteOutOfServiceException;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.EvaluationMode;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.RouteEvaluation;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.entity.RouteWeekdays;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RouteAlertScheduleSpawnServiceTest {

    @Mock RouteAlertScheduleRepository scheduleRepository;
    @Mock RouteArrivalAlertRepository alertRepository;
    @Mock RouteTravelTimeEstimator estimator;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();
    // now = 2026-06-23(화) 09:00
    LocalDateTime now = LocalDateTime.of(2026, 6, 23, 9, 0);

    RouteAlertScheduleSpawnService service() {
        return new RouteAlertScheduleSpawnService(scheduleRepository, alertRepository, estimator, properties);
    }

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

    private RouteAlertSchedule schedule(long id, boolean repeat, int mask, LocalTime arrivalTime) {
        RouteAlertSchedule s = RouteAlertSchedule.builder()
                .user(new User(123L))
                .startX(127.0).startY(37.5).endX(127.1).endY(37.6)
                .arrivalTime(arrivalTime).weekdaysMask(mask).repeat(repeat)
                .userLeadMinutes(10).bufferMinutes(5)
                .build();
        set(s, "id", id);
        given(scheduleRepository.findById(id)).willReturn(Optional.of(s));
        return s;
    }

    private RouteEvaluation eval(int durationSec) {
        return new RouteEvaluation(durationSec, RouteAlertReliability.MEDIUM, null, 12);
    }

    @Test
    void inFlightExists_skips() {
        RouteAlertSchedule s = schedule(1L, false, 0, LocalTime.of(10, 0));
        given(alertRepository.existsByScheduleIdAndStatusAndDeletedAtIsNull(1L, RouteAlertStatus.ACTIVE))
                .willReturn(true);

        service().processScheduleSpawn(1L, now);

        verify(estimator, never()).evaluate(any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void outsideWindow_skips() {
        // 일회성 20:00 → 11h > spawnLeadHours(6h) → skip
        schedule(2L, false, 0, LocalTime.of(20, 0));

        service().processScheduleSpawn(2L, now);

        verify(estimator, never()).evaluate(any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void insideWindow_viable_createsOccurrence_recordsSpawn() {
        RouteAlertSchedule s = schedule(3L, false, 0, LocalTime.of(10, 0)); // 1h away
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(1800)); // 30m
        given(alertRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service().processScheduleSpawn(3L, now);

        verify(alertRepository).save(any());
        assertThat(s.getLastSpawnedTargetArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 23, 10, 0));
        assertThat(s.getMaterializationRetryCount()).isZero();
    }

    @Test
    void tooLate_rollForward_noSave_noError() {
        // 09:20 도착(20m away, window 내), 소요 30m+버퍼5 → baselineLeaveBy 08:45 ≤ now → too-late
        RouteAlertSchedule s = schedule(4L, false, 0, LocalTime.of(9, 20));
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(1800));

        service().processScheduleSpawn(4L, now);

        verify(alertRepository, never()).save(any());
        assertThat(s.isActive()).isTrue();
        assertThat(s.getMaterializationRetryCount()).isZero();
        assertThat(s.getLastMaterializationError()).isNull();
    }

    @Test
    void outOfService_rollForward_noMetadata() {
        RouteAlertSchedule s = schedule(5L, false, 0, LocalTime.of(10, 0));
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC)))
                .willThrow(new RouteOutOfServiceException("막차 후"));

        service().processScheduleSpawn(5L, now);

        verify(alertRepository, never()).save(any());
        assertThat(s.isActive()).isTrue();
        assertThat(s.getMaterializationRetryCount()).isZero();
    }

    @Test
    void systemError_recordsRetryMetadata_staysActive() {
        RouteAlertSchedule s = schedule(6L, false, 0, LocalTime.of(10, 0));
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC)))
                .willThrow(new IllegalStateException("경로 없음"));

        service().processScheduleSpawn(6L, now);

        verify(alertRepository, never()).save(any());
        assertThat(s.isActive()).isTrue();
        assertThat(s.getMaterializationRetryCount()).isEqualTo(1);
        assertThat(s.getLastMaterializationError()).isEqualTo("경로 없음");
        assertThat(s.getNextMaterializationRetryAt())
                .isEqualTo(now.plusSeconds(properties.getSpawnBackoffBaseSeconds()));
    }

    @Test
    void backoffGate_skipsUntilRetryTime() {
        RouteAlertSchedule s = schedule(7L, false, 0, LocalTime.of(10, 0));
        set(s, "nextMaterializationRetryAt", now.plusMinutes(10));

        service().processScheduleSpawn(7L, now);

        verify(estimator, never()).evaluate(any(), any());
        verify(alertRepository, never()).save(any());
    }

    @Test
    void oneTime_lastOccurrenceDone_marksCompleted() {
        RouteAlertSchedule s = schedule(8L, false, 0, LocalTime.of(10, 0));
        s.recordSpawn(LocalDateTime.of(2026, 6, 22, 10, 0)); // 이미 1회 생성됨
        given(alertRepository.existsByScheduleIdAndStatusAndDeletedAtIsNull(8L, RouteAlertStatus.ACTIVE))
                .willReturn(false);
        RouteArrivalAlert done = RouteArrivalAlert.builder()
                .user(new User(123L)).scheduleId(8L).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(LocalDateTime.of(2026, 6, 22, 10, 0)).userLeadMinutes(10).bufferMinutes(5)
                .expiresAt(now).build();
        set(done, "status", RouteAlertStatus.DONE);
        given(alertRepository.findTopByScheduleIdOrderByIdDesc(8L)).willReturn(Optional.of(done));

        service().processScheduleSpawn(8L, now);

        assertThat(s.getStatus()).isEqualTo(RouteAlertScheduleStatus.COMPLETED);
        verify(estimator, never()).evaluate(any(), any());
    }

    @Test
    void repeat_insideWindow_nextWeekday() {
        int mask = RouteWeekdays.maskFromCodes(List.of("TUE"));
        RouteAlertSchedule s = schedule(9L, true, mask, LocalTime.of(9, 30)); // 오늘 화 09:30, 30m away
        given(estimator.evaluate(any(), eq(EvaluationMode.STATIC))).willReturn(eval(600)); // 10m → viable
        given(alertRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        service().processScheduleSpawn(9L, now);

        verify(alertRepository).save(any());
        assertThat(s.getLastSpawnedTargetArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 23, 9, 30));
    }
}
