package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BusAlertServiceTest {

    @Mock BusArrivalAlertRepository alertRepository;
    @Mock BusDataService busDataService;
    @Mock UserRepository userRepository;

    BusArrivalAlertProperties properties = new BusArrivalAlertProperties();
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T00:00:00Z"), ZoneId.of("Asia/Seoul"));

    BusAlertService service() {
        return new BusAlertService(alertRepository, busDataService, userRepository, properties, clock);
    }

    private static BusAlertRequest req(String stopId, String routeId) {
        BusAlertRequest r = new BusAlertRequest();
        set(r, "stopId", stopId);
        set(r, "routeId", routeId);
        set(r, "userEta", java.time.LocalDateTime.now().plusMinutes(10));
        set(r, "busArrivalMinutes", 8);
        return r;
    }

    private static void set(Object t, String f, Object v) {
        try { Field fld = t.getClass().getDeclaredField(f); fld.setAccessible(true); fld.set(t, v); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private void stubUserAndNames() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        BusRouteStop rs = mock(BusRouteStop.class);
        given(rs.getSeq()).willReturn(3);
        given(busDataService.findBusRouteByStopAndRoute("S1", "R1")).willReturn(List.of(rs));
        BusStop stop = mock(BusStop.class);
        given(stop.getStopName()).willReturn("강남역");
        given(busDataService.findStopById("S1")).willReturn(Optional.of(stop));
        BusRoute route = mock(BusRoute.class);
        given(route.getRouteName()).willReturn("간선143");
        given(busDataService.findRouteById("R1")).willReturn(Optional.of(route));
    }

    @Test
    void create_singleMapping_savesPendingWithServerNames() {
        stubUserAndNames();
        given(alertRepository.findFirstByUserAndStopIdAndRouteIdAndOrdAndStatusInAndDeletedAtIsNull(
                any(), any(), any(), any(), anyCollection())).willReturn(Optional.empty());
        given(alertRepository.save(any())).willAnswer(inv -> inv.getArgument(0));

        BusAlertResponse res = service().create(7L, req("S1", "R1"));

        assertThat(res.getStatus()).isEqualTo(AlertStatus.PENDING);
        assertThat(res.getStopName()).isEqualTo("강남역");
        assertThat(res.getRouteName()).isEqualTo("간선143");
        verify(alertRepository).save(any(BusArrivalAlert.class));
    }

    @Test
    void create_multipleMappings_throws() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        given(busDataService.findBusRouteByStopAndRoute("S1", "R1"))
                .willReturn(List.of(mock(BusRouteStop.class), mock(BusRouteStop.class)));

        assertThatThrownBy(() -> service().create(7L, req("S1", "R1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_stopNotFound_throws() {
        given(userRepository.findById(7L)).willReturn(Optional.of(new User(123L)));
        BusRouteStop rs = mock(BusRouteStop.class);
        given(rs.getSeq()).willReturn(3);
        given(busDataService.findBusRouteByStopAndRoute("S1", "R1")).willReturn(List.of(rs));
        given(busDataService.findStopById("S1")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service().create(7L, req("S1", "R1")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(alertRepository, never()).save(any());
    }

    @Test
    void create_existingActive_idempotentNoSave() {
        stubUserAndNames();
        BusArrivalAlert existing = BusArrivalAlert.builder()
                .user(new User(123L)).stopId("S1").routeId("R1").ord(3)
                .routeName("간선143").stopName("강남역")
                .userEta(java.time.LocalDateTime.now(clock).plusMinutes(10))
                .busArrivalMinutes(8)
                .expiresAt(java.time.LocalDateTime.now(clock).plusMinutes(60)).build();
        given(alertRepository.findFirstByUserAndStopIdAndRouteIdAndOrdAndStatusInAndDeletedAtIsNull(
                any(), any(), any(), any(), anyCollection())).willReturn(Optional.of(existing));

        BusAlertResponse res = service().create(7L, req("S1", "R1"));

        assertThat(res.getStatus()).isEqualTo(AlertStatus.PENDING);
        verify(alertRepository, never()).save(any());
    }
}
