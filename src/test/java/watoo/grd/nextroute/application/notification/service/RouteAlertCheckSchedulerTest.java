package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class RouteAlertCheckSchedulerTest {

    @Mock RouteArrivalAlertRepository alertRepository;
    @Mock RouteAlertCheckProcessor processor;

    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    RouteAlertCheckScheduler scheduler() {
        return new RouteAlertCheckScheduler(alertRepository, processor, clock);
    }

    private RouteArrivalAlert alert(long id) {
        RouteArrivalAlert a = RouteArrivalAlert.builder()
                .user(new User(1L)).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(LocalDateTime.now(clock).plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineLeaveByAt(LocalDateTime.now(clock).plusMinutes(45))
                .firstCheckAt(LocalDateTime.now(clock)).reliability(RouteAlertReliability.MEDIUM)
                .expiresAt(LocalDateTime.now(clock).plusMinutes(90)).build();
        try {
            Field f = RouteArrivalAlert.class.getSuperclass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(a, id);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
        return a;
    }

    @Test
    void run_unionsDueAndExpired_dedupById() {
        // nextAction 스캔=[1], expires 스캔=[1(중복),2(만료 고아)]. process 는 1·2 각각 1회.
        given(alertRepository.findByStatusAndNextActionAtLessThanEqualAndDeletedAtIsNull(
                eq(RouteAlertStatus.ACTIVE), any())).willReturn(List.of(alert(1L)));
        given(alertRepository.findByStatusAndExpiresAtLessThanEqualAndDeletedAtIsNull(
                eq(RouteAlertStatus.ACTIVE), any())).willReturn(List.of(alert(1L), alert(2L)));

        scheduler().run();

        verify(processor).process(1L);
        verify(processor).process(2L);
        verifyNoMoreInteractions(processor);
    }
}
