package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.RouteArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.RouteAlertRequest;
import watoo.grd.nextroute.application.notification.dto.RouteAlertScheduleResponse;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertSchedule;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertScheduleStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteAlertStatus;
import watoo.grd.nextroute.domain.notification.entity.RouteArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.RouteAlertScheduleRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertNotificationRepository;
import watoo.grd.nextroute.domain.notification.repository.RouteArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    @Mock RouteAlertScheduleRepository scheduleRepository;
    @Mock RouteArrivalAlertRepository alertRepository;
    @Mock RouteArrivalAlertNotificationRepository notificationRepository;
    @Mock RouteAlertScheduleSpawnService spawnService;
    @Mock UserRepository userRepository;

    RouteArrivalAlertProperties properties = new RouteArrivalAlertProperties();
    // 고정 now = 2026-06-23(화) 09:00 KST
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    RouteArrivalAlertService service() {
        return new RouteArrivalAlertService(
                scheduleRepository, alertRepository, notificationRepository, spawnService,
                userRepository, properties, clock);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(clock);
    }

    private RouteAlertRequest req(String hhmm, List<String> weekdays, boolean repeat, int userLead, Integer buffer) {
        RouteAlertRequest r = new RouteAlertRequest();
        set(r, "startX", 127.0);
        set(r, "startY", 37.5);
        set(r, "endX", 127.1);
        set(r, "endY", 37.6);
        set(r, "arrivalTime", LocalTime.parse(hhmm));
        set(r, "weekdays", weekdays);
        set(r, "repeat", repeat);
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

    private RouteAlertSchedule schedule(long id, boolean repeat, int mask, LocalTime arrivalTime) {
        RouteAlertSchedule s = RouteAlertSchedule.builder()
                .user(new User(123L))
                .startX(127.0).startY(37.5).endX(127.1).endY(37.6)
                .arrivalTime(arrivalTime).weekdaysMask(mask).repeat(repeat)
                .userLeadMinutes(10).bufferMinutes(5)
                .build();
        set(s, "id", id);
        return s;
    }

    @Test
    void create_savesSchedule_delegatesSpawn_returnsNextArrival() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        // 일회성 10:00, now 09:00 → 오늘 10:00
        RouteAlertSchedule saved = schedule(55L, false, 0, LocalTime.of(10, 0));
        given(scheduleRepository.save(any())).willReturn(saved);
        given(scheduleRepository.findById(55L)).willReturn(Optional.of(saved));
        given(alertRepository.findByScheduleIdAndStatusAndDeletedAtIsNull(55L, RouteAlertStatus.ACTIVE))
                .willReturn(List.of());

        RouteAlertScheduleResponse res = service().create(7L, req("10:00", null, false, 10, 5));

        verify(scheduleRepository).save(any());
        verify(spawnService).processScheduleSpawn(eq(55L), any());
        assertThat(res.getId()).isEqualTo(55L);
        assertThat(res.getNextArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 23, 10, 0));
        assertThat(res.getOccurrenceId()).isNull(); // spawn mock 이므로 미materialize
    }

    @Test
    void create_repeatWithoutWeekdays_throws() {
        assertThatThrownBy(() -> service().create(7L, req("08:00", List.of(), true, 10, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(scheduleRepository, never()).save(any());
        verify(spawnService, never()).processScheduleSpawn(any(), any());
    }

    @Test
    void create_userLeadNotAllowed_throws() {
        assertThatThrownBy(() -> service().create(7L, req("08:00", null, false, 7, 5)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(scheduleRepository, never()).save(any());
    }

    @Test
    void create_repeatComputesNextWeekday() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        int mask = watoo.grd.nextroute.domain.notification.entity.RouteWeekdays.maskFromCodes(List.of("WED"));
        RouteAlertSchedule saved = schedule(60L, true, mask, LocalTime.of(8, 0));
        given(scheduleRepository.save(any())).willReturn(saved);
        given(scheduleRepository.findById(60L)).willReturn(Optional.of(saved));
        given(alertRepository.findByScheduleIdAndStatusAndDeletedAtIsNull(60L, RouteAlertStatus.ACTIVE))
                .willReturn(List.of());

        // now 화 09:00, 선택 수 → 다음 수요일 2026-06-24 08:00
        RouteAlertScheduleResponse res = service().create(7L, req("08:00", List.of("WED"), true, 10, 5));

        assertThat(res.getNextArrivalAt()).isEqualTo(LocalDateTime.of(2026, 6, 24, 8, 0));
        assertThat(res.getWeekdays()).containsExactly("WED");
    }

    @Test
    void cancel_marksCanceled_propagatesToOccurrence() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        RouteAlertSchedule s = schedule(55L, false, 0, LocalTime.of(10, 0));
        given(scheduleRepository.findByIdAndUserAndDeletedAtIsNull(eq(55L), any())).willReturn(Optional.of(s));
        RouteArrivalAlert occ = RouteArrivalAlert.builder()
                .user(user).scheduleId(55L).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineLeaveByAt(now().plusMinutes(45)).firstCheckAt(now().plusMinutes(30))
                .expiresAt(now().plusMinutes(90)).build();
        set(occ, "id", 77L);
        given(alertRepository.findByScheduleIdAndStatusAndDeletedAtIsNull(55L, RouteAlertStatus.ACTIVE))
                .willReturn(List.of(occ));

        service().cancel(7L, 55L);

        assertThat(s.isActive()).isFalse();
        assertThat(occ.getStatus()).isEqualTo(RouteAlertStatus.CANCELED);
        verify(notificationRepository).cancelPendingByAlertId(77L);
    }

    @Test
    void getActive_filtersActive_batchLoadsOccurrence() {
        User user = new User(123L);
        given(userRepository.findById(7L)).willReturn(Optional.of(user));
        RouteAlertSchedule active = schedule(10L, true,
                watoo.grd.nextroute.domain.notification.entity.RouteWeekdays.maskFromCodes(List.of("MON")),
                LocalTime.of(9, 30));
        given(scheduleRepository.findByUserAndStatusAndDeletedAtIsNull(any(), eq(RouteAlertScheduleStatus.ACTIVE)))
                .willReturn(List.of(active));
        RouteArrivalAlert occ = RouteArrivalAlert.builder()
                .user(user).scheduleId(10L).startX(1.0).startY(2.0).endX(3.0).endY(4.0)
                .targetArrivalAt(now().plusMinutes(60)).userLeadMinutes(10).bufferMinutes(5)
                .baselineLeaveByAt(now().plusMinutes(45)).firstCheckAt(now().plusMinutes(30))
                .expiresAt(now().plusMinutes(90)).build();
        set(occ, "id", 99L);
        given(alertRepository.findActiveOccurrencesByScheduleIds(List.of(10L))).willReturn(List.of(occ));

        List<RouteAlertScheduleResponse> result = service().getActive(7L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(10L);
        assertThat(result.get(0).getOccurrenceId()).isEqualTo(99L);
    }
}
