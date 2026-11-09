package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.SearchRegionalArrivalService.Outcome;
import watoo.grd.nextroute.application.route.service.SearchRegionalArrivalService.Provider;
import watoo.grd.nextroute.application.route.service.SearchRegionalArrivalService.Result;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** search-regional-bus-wait PR-2: 검색용 경기·TAGO 도착 조회(몫·자리·기한). */
class SearchRegionalArrivalServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-09T06:00:00Z");

    private final GyeonggiBusArrivalPort gg = mock(GyeonggiBusArrivalPort.class);
    private final TagoBusArrivalPort tago = mock(TagoBusArrivalPort.class);
    private final TagoCityCodeLookup cities = mock(TagoCityCodeLookup.class);
    private final BusApiQuotaPort quota = mock(BusApiQuotaPort.class);
    private final TransferArrivalProperties props = new TransferArrivalProperties();
    private SearchRegionalArrivalService service;

    @BeforeEach
    void setUp() {
        props.setRegionalMaxConcurrent(1);
        service = new SearchRegionalArrivalService(gg, tago, cities, quota, props,
                new RouteSearchMetrics(new SimpleMeterRegistry()), Clock.fixed(NOW, ZoneOffset.UTC));
        when(quota.tryAcquire(anyString(), anyInt())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    private static GyeonggiBusArrival ggArrival(Integer min1, Integer min2) {
        return new GyeonggiBusArrival("234000026", min1, 3, null, null, min2, 9, null, null, "PASS");
    }

    @Test
    void 공급자_판정은_노선_id_모양으로() {
        assertThat(SearchRegionalArrivalService.providerOf("234000026", "202000208")).contains(Provider.GG);
        assertThat(SearchRegionalArrivalService.providerOf("CJB270011200", "CJB270000010")).contains(Provider.TAGO);
        assertThat(SearchRegionalArrivalService.providerOf("100100360", "1111")).isEmpty();
        assertThat(SearchRegionalArrivalService.providerOf("234000026", null)).isEmpty();
    }

    @Test
    void 경기_분_단위_도착을_초로_바꾸고_도착_시각_뒤_첫_버스를_고른다() {
        when(gg.getArrival("202000208", "234000026", true))
                .thenReturn(BusQueryResult.ok(List.of(ggArrival(3, 12))));

        Result r = service.start(Provider.GG, "234000026", "202000208").await(1000);

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(r.secondsAhead()).containsExactly(180, 720);
        assertThat(r.boardableAt(NOW.plusSeconds(200))).contains(NOW.plusSeconds(720));
        assertThat(r.boardableAt(NOW.plusSeconds(800))).isEmpty();   // 응답 밖 — 놓쳤다고 단정하지 않는다
    }

    @Test
    void TAGO_는_도시코드로_묻고_도시코드가_없으면_오류() {
        when(cities.cityCodeOfRoute("CJB270011200")).thenReturn(Optional.of("33010"));
        when(tago.getArrivals("33010", "CJB270000010", "CJB270011200"))
                .thenReturn(BusQueryResult.ok(List.of(new TagoBusArrival("CJB270011200", 240, 2, null))));
        when(cities.cityCodeOfRoute("XXX1")).thenReturn(Optional.empty());

        assertThat(service.start(Provider.TAGO, "CJB270011200", "CJB270000010").await(1000).secondsAhead())
                .containsExactly(240);
        assertThat(service.start(Provider.TAGO, "XXX1", "N1").await(1000).outcome()).isEqualTo(Outcome.ERROR);
    }

    @Test
    void 검색_몫이_없으면_부르지_않고_LIMITED() {
        when(quota.tryAcquire("search-gg-arrival", 2000)).thenReturn(false);

        assertThat(service.start(Provider.GG, "234000026", "202000208").await(1000).outcome()).isEqualTo(Outcome.LIMITED);
        verify(gg, never()).getArrival(anyString(), anyString(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void 풀이_차면_큐에서_기다리고_기한을_넘으면_TIMEOUT_큐까지_차면_LIMITED() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        when(gg.getArrival("202000208", "234000026", true)).thenAnswer(inv -> {
            release.await();
            return BusQueryResult.ok(List.of(ggArrival(3, null)));
        });

        SearchRegionalArrivalService.Pending slow = service.start(Provider.GG, "234000026", "202000208");
        assertThat(slow.await(50).outcome()).isEqualTo(Outcome.TIMEOUT);
        // 스레드 1 + 큐 4: 다음 넷은 큐에서 기다리고, 다섯째는 LIMITED
        List<SearchRegionalArrivalService.Pending> queued = new java.util.ArrayList<>();
        for (int i = 0; i < 4; i++) {
            queued.add(service.start(Provider.GG, "234000026", "202000208"));
        }
        assertThat(service.start(Provider.GG, "234000026", "202000208").await(50).outcome()).isEqualTo(Outcome.LIMITED);

        release.countDown();
        assertThat(slow.await(1000).outcome()).isEqualTo(Outcome.OK);   // 포기한 호출도 끝까지 돈다(공유 캐시를 채움)
        for (SearchRegionalArrivalService.Pending p : queued) {
            assertThat(p.await(1000).outcome()).isEqualTo(Outcome.OK);
        }
    }

    @Test
    void 경기_실패와_한도는_ERROR_LIMITED() {
        when(gg.getArrival("202000208", "234000026", true)).thenReturn(BusQueryResult.limited(), BusQueryResult.error());

        assertThat(service.start(Provider.GG, "234000026", "202000208").await(1000).outcome()).isEqualTo(Outcome.LIMITED);
        assertThat(service.start(Provider.GG, "234000026", "202000208").await(1000).outcome()).isEqualTo(Outcome.ERROR);
    }
}
