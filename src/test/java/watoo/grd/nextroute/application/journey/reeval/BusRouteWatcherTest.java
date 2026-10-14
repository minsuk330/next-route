package watoo.grd.nextroute.application.journey.reeval;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.journey.observability.JourneyReevalMetrics;
import watoo.grd.nextroute.common.lock.LeaderLease;
import watoo.grd.nextroute.testsupport.MutableClock;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@DisplayName("버스 노선 감시자")
class BusRouteWatcherTest {

    private static final String ROUTE = "100100022";
    private static final InterestKey.Bus STOP = new InterestKey.Bus("ST-1", ROUTE, 5);

    private final InterestIndex index = mock(InterestIndex.class);
    private final ReevaluationTrigger trigger = mock(ReevaluationTrigger.class);
    private final SearchTimeBusQueryPort api = mock(SearchTimeBusQueryPort.class);
    private final BusApiQuotaPort quota = mock(BusApiQuotaPort.class);
    private final BusRealtimeCacheWritePort cache = mock(BusRealtimeCacheWritePort.class);
    private final LeaderLease lease = mock(LeaderLease.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-14T09:00:00Z"), ZoneId.of("UTC"));
    private final ReevaluationProperties properties = new ReevaluationProperties();
    private BusRouteWatcher watcher;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setShadow(false);
        properties.setBusWatcher(true);
        properties.setBusWatcherDailyLimit(1000);
        given(lease.isLeader()).willReturn(true);
        given(quota.tryAcquire(anyString(), anyInt())).willReturn(true);
        given(index.busKeysByRoute()).willReturn(Map.of(ROUTE, Map.of(STOP, ReevaluationTier.URGENT)));
        given(index.journeysFor(anyCollection())).willReturn(Map.of(ReevaluationTier.URGENT, Set.of(9L)));
        given(trigger.request(any())).willReturn(new TriggerResult(Set.of(9L), Set.of()));
        watcher = new BusRouteWatcher(properties, index, trigger, api, quota, cache, lease,
                new JourneyReevalMetrics(registry), clock);
    }

    @Test
    @DisplayName("첫 조회는 기준선만, 버스가 한 정류장 다가오면 그 정류장을 지켜보는 여정을 깨운다")
    void busMovingOneStopTriggers() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong()))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 4, "3분후", 180))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();

        verify(trigger, times(1)).request(Map.of(ReevaluationTier.URGENT, Set.of(9L)));
    }

    @Test
    @DisplayName("도착 예정 초만 흔들린 것은 변화가 아니다")
    void secondsOnlyIsNotAChange() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong()))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "4분후", 260))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();

        verify(trigger, never()).request(any());
    }

    @Test
    @DisplayName("공급자 갱신 주기(10초)보다 자주 부르지 않는다 — 긴급이어도")
    void respectsCadence() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(5));
        watcher.tick();
        clock.advance(Duration.ofSeconds(5));
        watcher.tick();

        verify(api, times(2)).getArrInfoByRouteAll(eq(ROUTE), anyLong());
    }

    @Test
    @DisplayName("여유 등급 노선은 60초마다만 부른다")
    void relaxedRoutesAreSlow() {
        given(index.busKeysByRoute()).willReturn(Map.of(ROUTE, Map.of(STOP, ReevaluationTier.RELAXED)));
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(30));
        watcher.tick();
        clock.advance(Duration.ofSeconds(30));
        watcher.tick();

        verify(api, times(2)).getArrInfoByRouteAll(eq(ROUTE), anyLong());
    }

    @Test
    @DisplayName("tick 상한이 있으면 급한 노선부터 부른다")
    void urgentRoutesFirst() {
        properties.setBusMaxRoutesPerTick(1);
        InterestKey.Bus relaxedStop = new InterestKey.Bus("ST-9", "R-RELAXED", 1);
        given(index.busKeysByRoute()).willReturn(Map.of(
                "R-RELAXED", Map.of(relaxedStop, ReevaluationTier.RELAXED),
                ROUTE, Map.of(STOP, ReevaluationTier.URGENT)));
        given(api.getArrInfoByRouteAll(anyString(), anyLong())).willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))));

        watcher.tick();

        verify(api).getArrInfoByRouteAll(eq(ROUTE), anyLong());
        verify(api, never()).getArrInfoByRouteAll(eq("R-RELAXED"), anyLong());
    }

    @Test
    @DisplayName("전용 몫이 떨어지면 부르지 않고 감시 중단을 알린다 — 평가기 간격 폴링이 안전망")
    void quotaExhaustedDegrades() {
        given(quota.tryAcquire(anyString(), anyInt())).willReturn(false);

        watcher.tick();

        verifyNoInteractions(api);
        assertThat(registry.get(JourneyReevalMetrics.BUS_WATCHER_DEGRADED).gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("공급자가 막히면 이번 tick 은 멈춘다")
    void blockedProviderStopsTick() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.blocked());

        watcher.tick();

        assertThat(registry.get(JourneyReevalMetrics.BUS_WATCHER_DEGRADED).gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("연달아 세 번 빈 노선(서울 노선 아님)은 한동안 다시 부르지 않는다")
    void emptyRouteBacksOff() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.ok(List.of()));

        for (int i = 0; i < 3; i++) {
            watcher.tick();
            clock.advance(Duration.ofSeconds(10));
        }
        clock.advance(Duration.ofMinutes(10));
        watcher.tick();
        clock.advance(Duration.ofMinutes(55));
        watcher.tick();

        verify(api, times(4)).getArrInfoByRouteAll(eq(ROUTE), anyLong());
    }

    @Test
    @DisplayName("오류 한 번으로는 노선을 쉬게 하지 않는다 — 다음 주기에 다시 부른다")
    void singleErrorDoesNotBackOff() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong()))
                .willReturn(BusQueryResult.error())
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();

        verify(api, times(2)).getArrInfoByRouteAll(eq(ROUTE), anyLong());
    }

    @Test
    @DisplayName("리더 자격이 바뀌면 기준선을 새로 쌓는다 — 그사이 변화를 모른다")
    void epochChangeRebaselines() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong()))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 4, "3분후", 180))));
        given(lease.epoch()).willReturn(1L);
        watcher.tick();
        given(lease.epoch()).willReturn(2L);
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();

        verify(trigger, never()).request(any());
    }

    @Test
    @DisplayName("받은 응답을 정류장·순번별 도착 캐시에 채운다 — 깨어난 평가기가 공급자를 다시 부르지 않게")
    void primesArrivalCache() {
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.ok(List.of(
                arrival("ST-1", 5, "v1", 3, "5분후", 300), arrival("ST-2", 6, "v1", 3, "6분후", 360))));

        watcher.tick();

        verify(cache).putArrivals(eq("ST-1"), eq(ROUTE), eq(5), any());
        verify(cache).putArrivals(eq("ST-2"), eq(ROUTE), eq(6), any());
    }

    @Test
    @DisplayName("그림자 모드는 캐시를 채우지 않는다")
    void shadowDoesNotPrime() {
        properties.setShadow(true);
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong())).willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))));

        watcher.tick();

        verifyNoInteractions(cache);
    }

    @Test
    @DisplayName("반영 못 한 여정이 있으면 다음 조회에서 같은 변화를 다시 보낸다")
    void deferredIsResent() {
        given(trigger.request(any()))
                .willReturn(new TriggerResult(Set.of(), Set.of(9L)))
                .willReturn(new TriggerResult(Set.of(9L), Set.of()));
        given(api.getArrInfoByRouteAll(eq(ROUTE), anyLong()))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 3, "5분후", 300))))
                .willReturn(BusQueryResult.ok(List.of(arrival("ST-1", 5, "v1", 4, "3분후", 180))));

        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();
        clock.advance(Duration.ofSeconds(10));
        watcher.tick();

        verify(trigger, times(2)).request(any());
    }

    @Test
    @DisplayName("꺼져 있거나 리더가 아니면 부르지 않는다")
    void offOrNotLeaderDoesNothing() {
        properties.setBusWatcher(false);
        watcher.tick();
        properties.setBusWatcher(true);
        given(lease.isLeader()).willReturn(false);
        watcher.tick();

        verifyNoInteractions(api, trigger);
    }

    /** 첫 버스 차량·구간 순번·문구만 바꾸는 도착 한 줄. 나머지는 기존 픽스처와 같은 값. */
    private static BusArrivalInfo arrival(String stopId, int seq, String vehicle1, int section1,
                                          String msg1, int predict1) {
        return new BusArrivalInfo(
                ROUTE, "143", "143",
                stopId, "12390", "정류장",
                seq, "방향", 3, 12,
                "2026-10-14 18:00:00.0", "00", "Y",
                "20261014041000", "20261014222000",
                msg1, vehicle1, "p1", 0, section1, "이전정류장", "0", "0", "0",
                predict1, predict1, predict1, 3600,
                1.1, 1.2, 1.3, 1.4,
                60, 14.5,
                10, 4, 12, 2,
                "111000300", 2, 80, 20,
                5, 200, "111000310",
                10, 500, "111000320",
                15, 900, "111000330",
                "9분후", "v2", "p2", 1, 7, "두번째정류장", "0", "0", "0",
                540, 541, 542, 7200,
                2.1, 2.2, 2.3, 2.4,
                5, 13.5,
                20, 4, 24, 2,
                "111000301", 6, 280, 18,
                11, 700, "111000311",
                16, 1000, "111000321",
                21, 1400, "111000331");
    }
}
