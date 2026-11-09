package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** bus-boarding-flow S1: 정거장 띠에 쓸 정류장 이름. */
class BusApproachStopNamesTest {

    private static final String ROUTE = "100100118";

    private final BusRouteStopRepository routeStops = mock(BusRouteStopRepository.class);
    private final BusStopRepository stops = mock(BusStopRepository.class);
    private final InMemoryRegionalBusRouteStopStore regional = new InMemoryRegionalBusRouteStopStore();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-10T00:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private BusApproachStopNames names;

    @BeforeEach
    void setUp() {
        names = new BusApproachStopNames(routeStops, stops, regional, clock);
    }

    /** 순번 1..count, 정류장 id s{seq}, 이름 n{seq}. */
    private void givenSeoulRoute(int count) {
        List<BusRouteStop> route = new ArrayList<>();
        List<BusStop> catalog = new ArrayList<>();
        for (int seq = 1; seq <= count; seq++) {
            route.add(BusRouteStop.builder().routeId(ROUTE).stopId("s" + seq).seq(seq).build());
            catalog.add(BusStop.builder().stopId("s" + seq).stopName("n" + seq).build());
        }
        given(routeStops.findByRouteIdOrderBySeq(ROUTE)).willReturn(route);
        given(stops.findByStopIdIn(any())).willReturn(catalog);
    }

    @Test
    void pickSeqs_allWhenThreeOrFewer_elseBusStopPlusLastTwo() {
        assertThat(BusApproachStopNames.pickSeqs(10, 1)).containsExactly(9);
        assertThat(BusApproachStopNames.pickSeqs(10, 3)).containsExactly(7, 8, 9);
        assertThat(BusApproachStopNames.pickSeqs(10, 4)).containsExactly(6, 8, 9);
        assertThat(BusApproachStopNames.pickSeqs(40, 18)).containsExactly(22, 38, 39);
    }

    @Test
    void seoul_namesFromBusPositionToTheStopBeforeBoarding() {
        givenSeoulRoute(12);

        assertThat(names.seoul(ROUTE, 10, 2, "n8")).containsExactly("n8", "n9");
        assertThat(names.seoul(ROUTE, 10, 5, "n5")).containsExactly("n5", "n8", "n9");
    }

    @Test
    void seoul_firstNameMustBeWhereTheProviderSaysTheBusIs() {
        // 순번 세는 법이 어긋났다는 신호다. 한 칸 밀린 띠를 그리느니 안 그린다.
        givenSeoulRoute(12);

        assertThat(names.seoul(ROUTE, 10, 2, "n7")).isNull();
        assertThat(names.seoul(ROUTE, 10, 2, " n 8 ")).containsExactly("n8", "n9");   // 띄어쓰기 차이는 같은 이름
        assertThat(names.seoul(ROUTE, 10, 2, "n\u00A08\u3000")).containsExactly("n8", "n9");   // 줄바꿈 없는 공백·전각 공백도
        assertThat(names.seoul(ROUTE, 10, 2, null)).containsExactly("n8", "n9");       // 이름을 안 주면 대조 없이
        assertThat(names.seoul(ROUTE, 10, 2, " ")).containsExactly("n8", "n9");
    }

    @Test
    void seoul_unknownOrZeroStopsAway_isNullWithoutTouchingTheDb() {
        assertThat(names.seoul(ROUTE, 10, null, "n8")).isNull();
        assertThat(names.seoul(ROUTE, 10, 0, "n10")).isNull();

        verify(routeStops, never()).findByRouteIdOrderBySeq(anyString());
    }

    @Test
    void seoul_aMissingNameGivesNoListAtAll() {
        // 띠의 칸이 실제 정류장과 어긋나느니 띠를 안 그리는 편이 낫다.
        givenSeoulRoute(12);
        given(stops.findByStopIdIn(any())).willReturn(List.of(
                BusStop.builder().stopId("s9").stopName("n9").build()));

        assertThat(names.seoul(ROUTE, 10, 2, null)).isNull();
    }

    @Test
    void seoul_busBeforeTheFirstStopOfTheTable_isNull() {
        givenSeoulRoute(12);

        assertThat(names.seoul(ROUTE, 2, 3, null)).isNull();   // 순번 -1, 0 은 표에 없다
    }

    @Test
    void seoul_dbFailureIsSwallowed_arrivalMustStillGoOut() {
        given(routeStops.findByRouteIdOrderBySeq(ROUTE)).willThrow(new IllegalStateException("db down"));

        assertThat(names.seoul(ROUTE, 10, 2, null)).isNull();
    }

    @Test
    void routeTableIsRememberedForTenMinutes() {
        givenSeoulRoute(12);

        names.seoul(ROUTE, 10, 2, null);
        names.seoul(ROUTE, 9, 1, null);
        verify(routeStops, times(1)).findByRouteIdOrderBySeq(ROUTE);

        now.set(now.get().plusSeconds(601));
        names.seoul(ROUTE, 10, 2, null);
        verify(routeStops, times(2)).findByRouteIdOrderBySeq(ROUTE);
    }

    @Test
    void failedLoadIsNotRemembered() {
        given(routeStops.findByRouteIdOrderBySeq(ROUTE)).willThrow(new IllegalStateException("db down"));
        assertThat(names.seoul(ROUTE, 10, 2, null)).isNull();

        org.mockito.Mockito.reset(routeStops);
        givenSeoulRoute(12);
        assertThat(names.seoul(ROUTE, 10, 2, null)).containsExactly("n8", "n9");
    }

    @Test
    void rememberedRoutesAreCapped_expiredOnesGoFirst() {
        given(stops.findByStopIdIn(any())).willReturn(List.of());
        given(routeStops.findByRouteIdOrderBySeq(anyString())).willReturn(List.of());
        int half = BusApproachStopNames.MAX_ROUTES / 2;
        for (int i = 0; i < half; i++) {
            names.seoul("old-" + i, 10, 2, null);
        }
        now.set(now.get().plusSeconds(601));               // 앞의 절반만 만료
        for (int i = 0; i < half; i++) {
            names.seoul("live-" + i, 10, 2, null);
        }
        names.seoul("one-more", 10, 2, null);               // 꽉 찼다: 만료된 것만 치운다

        names.seoul("live-0", 10, 2, null);
        verify(routeStops, times(1)).findByRouteIdOrderBySeq("live-0");   // 살아 있는 것은 그대로다(통째로 비우지 않았다)
        names.seoul("old-0", 10, 2, null);
        verify(routeStops, times(2)).findByRouteIdOrderBySeq("old-0");    // 만료된 것은 다시 읽는다
    }

    @Test
    void sameRouteIsReadOnceWhenRequestsArriveTogether() throws Exception {
        // 만료 순간에 그 노선을 보던 화면들이 한꺼번에 표를 읽지 않는다: 먼저 온 읽기 하나를 나머지가 기다린다.
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        givenSeoulRoute(12);
        List<BusRouteStop> route = routeStops.findByRouteIdOrderBySeq(ROUTE);
        org.mockito.Mockito.clearInvocations(routeStops);
        given(routeStops.findByRouteIdOrderBySeq(ROUTE)).willAnswer(invocation -> {
            reading.countDown();
            release.await();
            return route;
        });
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try {
            java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(4);
            List<java.util.concurrent.Future<List<String>>> results = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                results.add(pool.submit(() -> {
                    started.countDown();
                    return names.seoul(ROUTE, 10, 2, null);
                }));
            }
            assertThat(reading.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // 넷이 전부 들어온 뒤에 읽기를 풀어 준다 — 늦게 시작한 스레드가 이미 채워진 캐시를 보고 통과하면 시험이 헛돈다.
            assertThat(started.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);   // 나머지 셋이 같은 읽기를 기다리는 자리에 설 틈
            release.countDown();
            for (java.util.concurrent.Future<List<String>> result : results) {
                assertThat(result.get(5, java.util.concurrent.TimeUnit.SECONDS)).containsExactly("n8", "n9");
            }
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        verify(routeStops, times(1)).findByRouteIdOrderBySeq(ROUTE);
    }

    private static RegionalRouteStop stop(String id, String name, int seq) {
        return new RegionalRouteStop(id, name, seq, null, null, null, null, null);
    }

    @Test
    void gyeonggi_findsTheBoardingSeqFromTheTable_andChecksTheBusStopName() {
        regional.replaceRoute(RegionalBusProvider.GG, "234000026", "720-2", null, List.of(
                stop("a", "가", 1), stop("b", "나", 2), stop("c", "다", 3), stop("d", "라", 4), stop("e", "마", 5)),
                Instant.EPOCH);

        assertThat(names.gyeonggi("234000026", "e", 2, "다")).containsExactly("다", "라");
        assertThat(names.gyeonggi("234000026", "e", 4, "가")).containsExactly("가", "다", "라");
        // 경기는 순번 세는 법(경유 정류장 포함 여부)을 실측하지 못했다: 이름이 다르거나 없으면 주지 않는다.
        assertThat(names.gyeonggi("234000026", "e", 2, "나")).isNull();
        assertThat(names.gyeonggi("234000026", "e", 2, null)).isNull();
    }

    @Test
    void gyeonggi_stopTwiceOnTheRoute_isNull() {
        // 왕복 노선: 같은 정류장이 두 번이면 어느 방향인지 모른다(순번 조회와 같은 규칙).
        regional.replaceRoute(RegionalBusProvider.GG, "234000026", "720-2", null, List.of(
                stop("a", "가", 1), stop("b", "나", 2), stop("a", "가", 3)), Instant.EPOCH);

        assertThat(names.gyeonggi("234000026", "a", 1, "나")).isNull();
        assertThat(names.gyeonggi("234000026", "b", 1, "가")).containsExactly("가");
    }

    @Test
    void gyeonggi_unknownRouteOrStopOrStopsAway_isNull() {
        regional.replaceRoute(RegionalBusProvider.GG, "234000026", "720-2", null, List.of(
                stop("a", "가", 1), stop("b", "나", 2)), Instant.EPOCH);
        regional.replaceRoute(RegionalBusProvider.TAGO, "BSB5200186000", "186", "21", List.of(
                stop("a", "가", 1), stop("b", "나", 2)), Instant.EPOCH);

        assertThat(names.gyeonggi("234000099", "b", 1, "가")).isNull();
        assertThat(names.gyeonggi("234000026", "zzz", 1, "가")).isNull();
        assertThat(names.gyeonggi("234000026", "b", null, "가")).isNull();
        assertThat(names.gyeonggi("BSB5200186000", "b", 1, "가")).isNull();   // TAGO 표의 노선은 경기 노선이 아니다
        assertThat(names.gyeonggi("234000026", "b", 1, "가")).containsExactly("가");
    }
}
