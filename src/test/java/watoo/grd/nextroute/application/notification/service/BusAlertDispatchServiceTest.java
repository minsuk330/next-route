package watoo.grd.nextroute.application.notification.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.InMemoryBusRealtimeCache;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.common.lock.FixedLeaderLease;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * 디스패치 단위 검증(outbox-only). dispatchDue 는 Toss 를 호출하지 않고 임계 도달 alert 를
 * {@link BusAlertOutboxEnqueueService#enqueueTossArrival} 로 적재만 한다. 발송은 relay 소관.
 */
@ExtendWith(MockitoExtension.class)
class BusAlertDispatchServiceTest {

    @Mock BusArrivalAlertRepository alertRepository;
    @Mock BusAlertStateService stateService;
    @Mock BusAlertOutboxEnqueueService outboxEnqueueService;
    @Mock BusApiPort busApiPort;

    BusArrivalAlertProperties props = new BusArrivalAlertProperties();
    Clock clock = Clock.fixed(Instant.parse("2026-06-23T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    InMemoryBusRealtimeCache cache = new InMemoryBusRealtimeCache();

    FixedLeaderLease lease = FixedLeaderLease.leader();

    BusAlertDispatchService service() {
        return new BusAlertDispatchService(
                alertRepository, stateService, outboxEnqueueService, busApiPort, cache, cache,
                props, clock, lease);
    }

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

    private BusArrivalInfo arrival(Integer sec1) {
        return BusArrivalInfoFixtures.arrivalWithSectionTime(sec1, null);
    }

    private BusArrivalInfo arrival(Integer sec1, Integer sec2) {
        return BusArrivalInfoFixtures.arrivalWithSectionTime(sec1, sec2);
    }

    private void due(List<BusArrivalAlert> alerts) {
        given(alertRepository.findDispatchable(any(), any(), any())).willReturn(alerts);
    }

    private void enqueueOk() {
        lenient().when(outboxEnqueueService.enqueueTossArrival(any(), anyInt())).thenReturn(true);
    }

    @Test
    void predictWithinThreshold_enqueuesOnly_noToss() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        enqueueOk();

        service().dispatchDue();

        // 60s → arrivalMin=1. Toss 는 dispatch 가 호출하지 않는다(발송=relay).
        verify(outboxEnqueueService).enqueueTossArrival(any(BusArrivalAlert.class), eq(1));
    }

    @Test
    void predictOverThreshold_doesNotEnqueue() {
        due(List.of(alert(1L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(300)));

        service().dispatchDue();

        verify(outboxEnqueueService, never()).enqueueTossArrival(any(), anyInt());
    }

    @Test
    void sameGroup_callsArrivalOnce_enqueuesEach() {
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        enqueueOk();

        service().dispatchDue();

        verify(busApiPort, times(1)).getArrInfoByStop("S1", "R1", "3"); // 그룹당 조회 1회
        verify(outboxEnqueueService, times(2)).enqueueTossArrival(any(), anyInt());
    }

    @Test
    void lostLeadership_skipsRemainingGroups() {
        // 그룹 둘(정류장 S1, S2). 첫 그룹의 공급자 호출 중에 리더를 잃으면 둘째 그룹은 공급자를 부르지 않는다.
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S2", "R1", 3)));
        // 그룹 순서는 HashMap 이라 정해져 있지 않다 — 먼저 불린 그룹에서 리더를 잃는다.
        given(busApiPort.getArrInfoByStop(any(), any(), any())).willAnswer(inv -> {
            lease.set(false);
            return List.of(arrival(60));
        });
        enqueueOk();

        service().dispatchDue();

        verify(busApiPort, times(1)).getArrInfoByStop(any(), any(), any());
    }

    @Test
    void perUserCycleCap_limitsEnqueue() {
        props.setPerUserCycleCap(1);
        // 동일 userKey(123) 2건 → cap 1 이므로 1건만 enqueue.
        due(List.of(alert(1L, "S1", "R1", 3), alert(2L, "S1", "R1", 3)));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        enqueueOk();

        service().dispatchDue();

        verify(outboxEnqueueService, times(1)).enqueueTossArrival(any(), anyInt());
    }

    @Test
    void picksBusArrivingAfterUserEta() {
        // userEta = now + 100s. bus1=60s(사용자 도착 전, 제외), bus2=119s(이후 + 임계 내) → enqueue(arrivalMin=2)
        BusArrivalAlert a = alert(1L, "S1", "R1", 3, now().plusSeconds(100));
        due(List.of(a));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60, 119)));
        enqueueOk();

        service().dispatchDue();

        verify(outboxEnqueueService).enqueueTossArrival(eq(a), eq(2));
    }

    @Test
    void noBusAfterUserEta_doesNotEnqueue() {
        // userEta = now + 100s. bus1=60s(제외), bus2 없음 → 대상 없음
        BusArrivalAlert a = alert(1L, "S1", "R1", 3, now().plusSeconds(100));
        due(List.of(a));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60, null)));

        service().dispatchDue();

        verify(outboxEnqueueService, never()).enqueueTossArrival(any(), anyInt());
    }

    @Test
    void oneAlertException_doesNotBlockOthers() {
        // 같은 그룹 2건. 첫 건 enqueue 예상외 예외 → 둘째 건은 정상 enqueue.
        BusArrivalAlert a1 = alert(1L, "S1", "R1", 3);
        BusArrivalAlert a2 = alert(2L, "S1", "R1", 3);
        due(List.of(a1, a2));
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(60)));
        given(outboxEnqueueService.enqueueTossArrival(eq(a1), anyInt())).willThrow(new RuntimeException("boom"));
        given(outboxEnqueueService.enqueueTossArrival(eq(a2), anyInt())).willReturn(true);

        service().dispatchDue();

        verify(outboxEnqueueService).enqueueTossArrival(eq(a2), anyInt());
    }

    @Test
    void cacheReadEnabled_reusesRealtimeCache_acrossCycles_reducingBusApiCalls() {
        // Step E: 같은 그룹을 여러 사이클 돌려도 provider 는 miss 1회만. 이후 캐시 hit(busApiCalls 절감).
        props.setCacheReadEnabled(true);
        // 임계 밖(300s)이라 enqueue 로직은 타지 않음 — 도착 로드(캐시) 경로만 격리 검증.
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(300)));

        due(List.of(alert(1L, "S1", "R1", 3)));
        service().dispatchDue();   // cycle1: miss → provider 호출 + 프라이밍
        due(List.of(alert(1L, "S1", "R1", 3)));
        service().dispatchDue();   // cycle2: hit → provider 미호출
        due(List.of(alert(1L, "S1", "R1", 3)));
        service().dispatchDue();   // cycle3: hit

        verify(busApiPort, times(1)).getArrInfoByStop("S1", "R1", "3");
        assertThat(cache.getArrivals("S1", "R1", 3)).isPresent();
    }

    @Test
    void cacheReadDisabled_alwaysCallsProvider() {
        // flag off(기본): 캐시 미사용 → 매 사이클 provider 직접 호출.
        given(busApiPort.getArrInfoByStop("S1", "R1", "3")).willReturn(List.of(arrival(300)));

        due(List.of(alert(1L, "S1", "R1", 3)));
        service().dispatchDue();
        due(List.of(alert(1L, "S1", "R1", 3)));
        service().dispatchDue();

        verify(busApiPort, times(2)).getArrInfoByStop("S1", "R1", "3");
        assertThat(cache.getArrivals("S1", "R1", 3)).isEmpty();
    }
}
