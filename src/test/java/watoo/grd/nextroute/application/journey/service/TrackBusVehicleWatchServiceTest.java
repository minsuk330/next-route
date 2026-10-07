package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Provider;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Snapshot;
import watoo.grd.nextroute.application.bus.port.out.BusPositionPort.Vehicle;
import watoo.grd.nextroute.application.bus.port.out.BusVehiclePositionCachePort;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.domain.journey.entity.JourneyKind;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 관찰 → 출발 사건 → 승차 시각 대조 흐름. 대전 1001(TAGO), 승차 정류장 순번 9.
 * 승차 시각(기기) = T0+28초, 대조는 창(60) + 근접 간격(15) 이 지난 T0+103초부터.
 */
@DisplayName("단독 추적 버스 관찰·대조")
class TrackBusVehicleWatchServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");
    private static final String ROUTE = "DJB30300151";
    private static final String BOARD = "DJB8007626";

    private final MutableClock clock = new MutableClock(T0);
    private final JourneySessionRepository sessionRepository = mock(JourneySessionRepository.class);
    private final JourneyLegRepository legRepository = mock(JourneyLegRepository.class);
    private final JourneyLegRouteOptionRepository optionRepository = mock(JourneyLegRouteOptionRepository.class);
    private final JourneyEventRepository eventRepository = mock(JourneyEventRepository.class);
    private final BusPositionPort positionPort = mock(BusPositionPort.class);
    private final BusApiQuotaPort quotaPort = mock(BusApiQuotaPort.class);
    private final BusVehiclePositionCachePort cache = mock(BusVehiclePositionCachePort.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final JourneyTrackProperties properties = new JourneyTrackProperties();
    private final InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();

    private TrackBusVehicleWatchService service;
    private JourneySession session;
    private JourneyLeg wait;
    private JourneyLeg ride;
    private JourneyLegRouteOption option;

    @BeforeEach
    void setUp() {
        List<RegionalRouteStop> stops = new ArrayList<>();
        for (int seq = 1; seq <= 14; seq++) {
            stops.add(new RegionalRouteStop(seq == 9 ? BOARD : "DJB" + seq, "s" + seq, seq, false, null, false,
                    null, null));
        }
        store.replaceRoute(RegionalBusProvider.TAGO, ROUTE, "1001", "25", stops, T0);
        TagoCityCodeLookup lookup = new TagoCityCodeLookup(store);
        lookup.refresh();

        session = JourneySession.builder().userId(1L).kind(JourneyKind.TRACK).startedAt(T0)
                .guidanceLeaseUntil(T0.plusSeconds(180)).routeSnapshot(new ObjectMapper().createObjectNode()).build();
        ReflectionTestUtils.setField(session, "id", 7L);
        wait = leg(100L, 0, JourneyLegType.WAIT_BUS);
        ride = leg(101L, 1, JourneyLegType.RIDE_BUS);
        option = mock(JourneyLegRouteOption.class);
        given(option.getSourceRouteId()).willReturn(ROUTE);
        given(optionRepository.findById(99L)).willReturn(Optional.of(option));
        given(sessionRepository.findByKindAndStatusAndDeletedAtIsNull(JourneyKind.TRACK, JourneyStatus.ACTIVE))
                .willReturn(List.of(session));
        given(legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(7L, 1))
                .willReturn(List.of(wait, ride));
        given(quotaPort.tryAcquire(anyString(), anyInt())).willReturn(true);
        given(legRepository.assignVehicle(anyLong(), anyString(), anyString(), anyString(), any())).willReturn(1);
        given(legRepository.finishVehicleUnmatched(anyLong(), any())).willReturn(1);
        JourneyEventRepository.TransitionTime transition = mock(JourneyEventRepository.TransitionTime.class);
        given(transition.getClientOccurredAt()).willReturn(T0.plusSeconds(28));
        given(transition.getServerReceivedAt()).willReturn(T0.plusSeconds(29));
        given(eventRepository.findAppliedTransitionInto(7L, 1, 1)).willReturn(Optional.of(transition));

        service = new TrackBusVehicleWatchService(sessionRepository, legRepository, optionRepository, eventRepository,
                store, lookup, positionPort, quotaPort, cache, properties, meters,
                mock(PlatformTransactionManager.class), clock);
    }

    @Test
    @DisplayName("기다리는 동안 관찰 → 탄 뒤 창이 지나면 승차 정류장을 떠난 그 차를 고정(TAGO = 차량번호)")
    void matchesSingleDeparture() {
        positions(snap(new Vehicle("대전1", "대전1", 8, false)));
        tickAt(0);                          // 근접 → 다음 관찰 15초 뒤
        positions(snap(new Vehicle("대전1", "대전1", 9, false)));
        tickAt(15);
        boardAt();                          // 앱이 RIDE_BUS 로 넘어감(기기 T0+28)
        positions(snap(new Vehicle("대전1", "대전1", 10, false)));
        tickAt(30);                         // 출발 사건(대전1, T0+30)
        tickAt(90);
        verify(legRepository, never()).assignVehicle(anyLong(), anyString(), anyString(), anyString(), any());

        tickAt(105);

        verify(legRepository).assignVehicle(eq(101L), eq("대전1"), eq("PLAIN_NO"), eq("AUTO_MATCH"), any());
        // 0·15·30 + 탄 뒤 대조 대기 중엔 근접 간격(15초)으로 계속 본다(90·105).
        verify(positionPort, times(5)).positions(Provider.TAGO, ROUTE, "25");
        verify(cache, times(5)).put(eq(Provider.TAGO), eq(ROUTE), any());
        assertThat(meters.counter("bus.match", "provider", "TAGO", "result", "matched").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("근처에 차가 없으면 60초 간격으로만 본다")
    void idlePolling() {
        positions(snap(new Vehicle("대전1", "대전1", 2, false)));
        tickAt(0);
        tickAt(15);
        tickAt(45);
        tickAt(60);

        verify(positionPort, times(2)).positions(Provider.TAGO, ROUTE, "25");
    }

    @Test
    @DisplayName("붙어 떠난 두 대 → 고정하지 않고 판정을 끝낸다")
    void ambiguous() {
        positions(snap(new Vehicle("대전1", "대전1", 9, false), new Vehicle("대전2", "대전2", 9, false)));
        tickAt(15);
        boardAt();
        positions(snap(new Vehicle("대전1", "대전1", 10, false), new Vehicle("대전2", "대전2", 10, false)));
        tickAt(30);

        tickAt(105);

        verify(legRepository, never()).assignVehicle(anyLong(), anyString(), anyString(), anyString(), any());
        verify(legRepository).finishVehicleUnmatched(eq(101L), any());
        assertThat(meters.counter("bus.match", "provider", "TAGO", "result", "ambiguous").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("0대면 30초 더 기다렸다 포기")
    void noneThenGiveUp() {
        positions(snap(new Vehicle("대전1", "대전1", 3, false)));
        boardAt();
        tickAt(30);
        tickAt(105);
        verify(legRepository, never()).finishVehicleUnmatched(anyLong(), any());

        tickAt(135);

        verify(legRepository).finishVehicleUnmatched(eq(101L), any());
    }

    @Test
    @DisplayName("예산 소진이면 차량 없이 판정을 끝낸다(추적은 계속)")
    void budgetExhausted() {
        given(quotaPort.tryAcquire(anyString(), anyInt())).willReturn(false);
        boardAt();
        tickAt(30);
        tickAt(105);

        verify(positionPort, never()).positions(any(), any(), any());
        verify(legRepository).finishVehicleUnmatched(eq(101L), any());
        assertThat(meters.counter("bus.match", "provider", "TAGO", "result", "budget").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("판정이 끝난 탑승 단계는 더 보지 않는다")
    void finishedRideIsNotWatched() {
        boardAt();
        ReflectionTestUtils.setField(ride, "vehicleMatchedAt", T0);
        positions(snap(new Vehicle("대전1", "대전1", 9, false)));

        assertThat(service.tick()).isZero();
        verify(positionPort, never()).positions(any(), any(), any());
    }

    @Test
    @DisplayName("기기 시계가 2분 넘게 틀리면 서버 수신 시각을 승차 시각으로")
    void clockSuspect() {
        JourneyEventRepository.TransitionTime skewed = mock(JourneyEventRepository.TransitionTime.class);
        given(skewed.getClientOccurredAt()).willReturn(T0.minusSeconds(600));
        given(skewed.getServerReceivedAt()).willReturn(T0.plusSeconds(29));
        given(eventRepository.findAppliedTransitionInto(7L, 1, 1)).willReturn(Optional.of(skewed));
        boardAt();

        assertThat(service.boardedAt(new TrackBusVehicleWatchService.Target(session, ride,
                new TrackBusVehicleWatchService.RouteKey(Provider.TAGO, ROUTE, "25"), 9, true)))
                .isEqualTo(T0.plusSeconds(29));
    }

    @Test
    @DisplayName("서울은 보강된 서울 노선 id·승차 순번으로 본다")
    void seoulUsesBoardingSeq() {
        given(option.getRouteId()).willReturn("100100093");
        given(option.getBoardingSeq()).willReturn(40);
        positions(snap(new Vehicle("V1", "서울1", 39, false)));

        tickAt(0);

        verify(positionPort).positions(Provider.SEOUL, "100100093", null);
        verify(quotaPort).tryAcquire("track-bus-pos-seoul", 2_000);
    }

    // ── 도우미 ──

    private void boardAt() {
        session.moveToLeg(1);
        ride.changeState(JourneyLegState.ACTIVE, T0.plusSeconds(29));
    }

    private void tickAt(int second) {
        clock.at = T0.plusSeconds(second);
        service.tick();
    }

    private void positions(Snapshot snapshot) {
        given(positionPort.positions(any(), any(), any())).willAnswer(inv ->
                Snapshot.ok(snapshot.vehicles(), clock.instant()));
    }

    private static Snapshot snap(Vehicle... vehicles) {
        return Snapshot.ok(List.of(vehicles), T0);
    }

    private static JourneyLeg leg(Long id, int index, JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(7L).routeRevision(1).legIndex(index).type(type)
                .state(index == 0 ? JourneyLegState.ACTIVE : JourneyLegState.PENDING)
                .sourceStartStopId(BOARD).build();
        ReflectionTestUtils.setField(leg, "id", id);
        ReflectionTestUtils.setField(leg, "selectedRouteOptionId", 99L);
        return leg;
    }

    static final class MutableClock extends Clock {
        Instant at;

        MutableClock(Instant at) {
            this.at = at;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return at;
        }
    }
}
