package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusAlertDispatchServiceTest {

    @Mock BusArrivalAlertRepository alertRepository;
    @Mock BusAlertStateService stateService;
    @Mock BusApiPort busApiPort;
    @Mock TossMessengerPort tossMessengerPort;

    BusArrivalAlertProperties props = new BusArrivalAlertProperties();
    TossMessengerProperties msgProps = new TossMessengerProperties();
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T03:00:00Z"), ZoneId.of("Asia/Seoul"));

    BusAlertDispatchService service() {
        msgProps.setBusArrivalTemplateSetCode("BUS_ARRIVAL");
        return new BusAlertDispatchService(
                alertRepository, stateService, busApiPort, tossMessengerPort, props, msgProps, clock);
    }

    // 디스패치 now = LocalDateTime.now(clock)
    private java.time.LocalDateTime now() {
        return java.time.LocalDateTime.now(clock);
    }

    private BusArrivalAlert alert(long id, String stop, String route, int ord) {
        return alert(id, stop, route, ord, now()); // userEta = now → 모든 비음수 버스가 대상
    }

    private BusArrivalAlert alert(long id, String stop, String route, int ord, java.time.LocalDateTime userEta) {
        BusArrivalAlert a = mock(BusArrivalAlert.class);
        lenient().when(a.getId()).thenReturn(id);
        lenient().when(a.getStopId()).thenReturn(stop);
        lenient().when(a.getRouteId()).thenReturn(route);
        lenient().when(a.getOrd()).thenReturn(ord);
        lenient().when(a.getRouteName()).thenReturn("간선143");
        lenient().when(a.getStopName()).thenReturn("강남역");
        lenient().when(a.getUserEta()).thenReturn(userEta);
        lenient().when(a.getUser()).thenReturn(new User(123L));
        return a;
    }

    private BusArrivalInfo arrival(Integer kalPredict) {
        return BusArrivalInfoFixtures.arrivalWithKalPredict(kalPredict, null);
    }

    private BusArrivalInfo arrival(Integer kal1, Integer kal2) {
        return BusArrivalInfoFixtures.arrivalWithKalPredict(kal1, kal2);
    }

    private void due(List<BusArrivalAlert> alerts) {
        given(alertRepository.findDispatchable(any(), any(), any())).willReturn(alerts);
    }

    @Test
    void predictWithinThreshold_sendsAndMarksSent() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(1L)).willReturn(true);

        service().dispatchDue();

        verify(tossMessengerPort).sendMessage(eq(123L), eq("BUS_ARRIVAL"), anyMap());
        verify(stateService).markSent(1L);
    }

    @Test
    void predictOverThreshold_doesNotSend() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(300)));

        service().dispatchDue();

        verify(stateService, never()).claim(anyLong());
        verify(tossMessengerPort, never()).sendMessage(anyLong(), any(), anyMap());
    }

    @Test
    void sameGroup_callsArrivalOnce() {
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(anyLong())).willReturn(true);

        service().dispatchDue();

        verify(busApiPort, times(1)).getArrInfoByStop("S1", "R1", "3");
        verify(tossMessengerPort, times(2)).sendMessage(anyLong(), any(), anyMap());
    }

    @Test
    void permanentFailure_marksPermanent() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(1L)).willReturn(true);
        doThrow(new TossMessengerException("bad", true))
                .when(tossMessengerPort).sendMessage(anyLong(), any(), anyMap());

        service().dispatchDue();

        verify(stateService).markPermanentFailure(eq(1L), any());
        verify(stateService, never()).markSent(anyLong());
    }

    @Test
    void transientFailure_marksTransient() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(1L)).willReturn(true);
        doThrow(new TossMessengerException("timeout", false))
                .when(tossMessengerPort).sendMessage(anyLong(), any(), anyMap());

        service().dispatchDue();

        verify(stateService).markTransientFailure(eq(1L), any());
    }

    @Test
    void perUserCycleCap_limitsSends() {
        props.setPerUserCycleCap(1);
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(anyLong())).willReturn(true);

        service().dispatchDue();

        verify(tossMessengerPort, times(1)).sendMessage(anyLong(), any(), anyMap());
    }

    @Test
    void picksBusArrivingAfterUserEta() {
        // userEta = now + 100s. bus1=60s(사용자 도착 전, 제외), bus2=119s(이후 + 임계 내) → 발송
        BusArrivalAlert a = alert(1L, "S1", "R1", 3, now().plusSeconds(100));
        due(List.of(a));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60, 119)));
        given(stateService.claim(1L)).willReturn(true);

        service().dispatchDue();

        verify(tossMessengerPort).sendMessage(eq(123L), eq("BUS_ARRIVAL"), anyMap());
        verify(stateService).markSent(1L);
    }

    @Test
    void noBusAfterUserEta_doesNotSend() {
        // userEta = now + 100s. bus1=60s(제외), bus2 없음 → 대상 없음
        BusArrivalAlert a = alert(1L, "S1", "R1", 3, now().plusSeconds(100));
        due(List.of(a));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60, null)));

        service().dispatchDue();

        verify(stateService, never()).claim(anyLong());
        verify(tossMessengerPort, never()).sendMessage(anyLong(), any(), anyMap());
    }

    @Test
    void oneAlertException_doesNotBlockOthers() {
        // 같은 그룹 2건. 첫 건 claim에서 예상외 예외 → 둘째 건은 정상 발송돼야 함.
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(1L)).willThrow(new RuntimeException("boom"));
        given(stateService.claim(2L)).willReturn(true);

        service().dispatchDue();

        verify(stateService).markSent(2L);
        verify(tossMessengerPort, times(1)).sendMessage(anyLong(), any(), anyMap());
    }

    @Test
    void claimReturnsFalse_skipsSend() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(stateService.claim(1L)).willReturn(false);

        service().dispatchDue();

        verify(tossMessengerPort, never()).sendMessage(anyLong(), any(), anyMap());
    }
}
