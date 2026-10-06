package watoo.grd.nextroute.application.regionalbus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalbus.config.RegionalBusCatalogProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusCatalogLoadResult;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.dto.TagoCity;
import watoo.grd.nextroute.application.regionalbus.dto.TagoRoute;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteSourcePort;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore.RouteKey;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusSourceException;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 적재 서비스 계약(#267): 노선 단위 교체, 경기 분기, 실패·0건이 옛 값을 지키는지, 폐선 정리 조건.
 */
class RegionalBusCatalogLoadServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-01T18:30:00Z");

    private final FakeSource source = new FakeSource();
    private final InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();
    private final TagoCityCodeLookup lookup = new TagoCityCodeLookup(store);
    private RegionalBusCatalogLoadService service;

    @BeforeEach
    void setUp() {
        RegionalBusCatalogProperties properties = new RegionalBusCatalogProperties();
        properties.setConcurrency(2);
        properties.setRetryBackoffMs(0);
        service = new RegionalBusCatalogLoadService(source, store, properties, lookup,
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private static RegionalRouteStop stop(String id, int seq) {
        return new RegionalRouteStop(id, id, seq, null, null, null, 36.0, 127.0);
    }

    @Test
    @DisplayName("TAGO 노선은 TAGO 로, GGB 노선은 접두사를 떼고 경기 API 로 적재한다. 적재 뒤 조회기가 새 표를 본다")
    void loadsTagoAndGyeonggi() {
        source.cities = List.of(new TagoCity("32050", "태백시"), new TagoCity("31010", "수원시"));
        source.routes.put("32050", List.of(new TagoRoute("32050", "TSB309000001", "1")));
        source.routes.put("31010", List.of(new TagoRoute("31010", "GGB233000031", "7770")));
        source.tagoStops.put("TSB309000001", List.of(stop("TSB1", 1), stop("TSB2", 2)));
        source.ggStops.put("233000031", List.of(stop("277103108", 1)));

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.routes()).isEqualTo(2);
        assertThat(result.loaded()).isEqualTo(2);
        assertThat(store.routes.get(new RouteKey(RegionalBusProvider.TAGO, "TSB309000001")).cityCode())
                .isEqualTo("32050");
        assertThat(store.routes).containsKey(new RouteKey(RegionalBusProvider.GG, "233000031"));
        assertThat(source.ggCalls).containsExactly("233000031");
        // 접두사 표에 없는 TSB 도 노선으로 도시코드가 나온다
        assertThat(lookup.cityCodeOfRoute("TSB309000001")).contains("32050");
    }

    @Test
    @DisplayName("한 노선 실패·0건이어도 나머지는 적재하고, 그 노선의 옛 값은 남는다(폐선으로 지우지 않는다)")
    void failureAndEmptyKeepOldRows() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB2", "2", "25", List.of(stop("OLD", 1)), NOW.minusSeconds(86400));
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB3", "3", "25", List.of(stop("OLD", 1)), NOW.minusSeconds(86400));
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routes.put("25", List.of(
                new TagoRoute("25", "DJB1", "1"),
                new TagoRoute("25", "DJB2", "2"),
                new TagoRoute("25", "DJB3", "3")));
        source.tagoStops.put("DJB1", List.of(stop("A", 1)));
        source.failing.put("DJB2", RegionalBusSourceException.failed("timeout"));
        source.tagoStops.put("DJB3", List.of());

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.loaded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.empty()).isEqualTo(1);
        assertThat(result.pruned()).isZero();
        assertThat(store.routes.get(new RouteKey(RegionalBusProvider.TAGO, "DJB2")).stops())
                .extracting(RegionalRouteStop::stopId).containsExactly("OLD");
        assertThat(store.routes.get(new RouteKey(RegionalBusProvider.TAGO, "DJB3")).stops())
                .extracting(RegionalRouteStop::stopId).containsExactly("OLD");
    }

    @Test
    @DisplayName("노선 목록에 더는 없는 노선만 폐선으로 지운다")
    void prunesRoutesMissingFromListing() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB9", "9", "25", List.of(stop("X", 1)), NOW.minusSeconds(86400));
        store.replaceRoute(RegionalBusProvider.GG, "200000001", "9", "31010", List.of(stop("Y", 1)), NOW.minusSeconds(86400));
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routes.put("25", List.of(new TagoRoute("25", "DJB1", "1")));
        source.tagoStops.put("DJB1", List.of(stop("A", 1)));

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.pruned()).isEqualTo(2);
        assertThat(store.routes.keySet()).containsExactly(new RouteKey(RegionalBusProvider.TAGO, "DJB1"));
    }

    @Test
    @DisplayName("포털 공통 오류(키 미등록 등)를 만나면 멈추고, 폐선 정리를 하지 않는다")
    void blockingErrorAborts() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB9", "9", "25", List.of(stop("X", 1)), NOW.minusSeconds(86400));
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routes.put("25", List.of(new TagoRoute("25", "DJB1", "1")));
        source.failing.put("DJB1", RegionalBusSourceException.blocking("portal error reasonCode=30"));

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.aborted()).isTrue();
        assertThat(result.pruned()).isZero();
        assertThat(store.routes).containsKey(new RouteKey(RegionalBusProvider.TAGO, "DJB9"));
    }

    @Test
    @DisplayName("노선 목록을 못 받으면 아무것도 바꾸지 않는다")
    void listingFailureChangesNothing() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB9", "9", "25", List.of(stop("X", 1)), NOW.minusSeconds(86400));
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routesFailure = RegionalBusSourceException.failed("timeout");

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.aborted()).isTrue();
        assertThat(store.routes).containsKey(new RouteKey(RegionalBusProvider.TAGO, "DJB9"));
    }

    @Test
    @DisplayName("동시 세션 한도(99)는 다시 시도해서 넘긴다")
    void retriesSessionLimit() {
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routes.put("25", List.of(new TagoRoute("25", "DJB1", "1")));
        source.tagoStops.put("DJB1", List.of(stop("A", 1)));
        source.retryableFailuresBeforeSuccess.set(2);

        RegionalBusCatalogLoadResult result = service.load();

        assertThat(result.loaded()).isEqualTo(1);
        assertThat(result.failed()).isZero();
    }

    @Test
    @DisplayName("순번 0·정류장 id 없는 칸은 버린다")
    void dropsInvalidRows() {
        source.cities = List.of(new TagoCity("25", "대전"));
        source.routes.put("25", List.of(new TagoRoute("25", "DJB1", "1")));
        source.tagoStops.put("DJB1", List.of(stop("A", 1), stop(null, 2), stop("C", 0)));

        service.load();

        assertThat(store.routes.get(new RouteKey(RegionalBusProvider.TAGO, "DJB1")).stops())
                .extracting(RegionalRouteStop::stopId).containsExactly("A");
    }

    /** 가짜 원천. 노선별 실패를 심을 수 있다. */
    static class FakeSource implements RegionalBusRouteSourcePort {
        List<TagoCity> cities = List.of();
        final Map<String, List<TagoRoute>> routes = new HashMap<>();
        final Map<String, List<RegionalRouteStop>> tagoStops = new HashMap<>();
        final Map<String, List<RegionalRouteStop>> ggStops = new HashMap<>();
        final Map<String, RegionalBusSourceException> failing = new HashMap<>();
        final List<String> ggCalls = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger retryableFailuresBeforeSuccess = new AtomicInteger();
        RegionalBusSourceException routesFailure;

        @Override
        public List<TagoCity> tagoCities() {
            return cities;
        }

        @Override
        public List<TagoRoute> tagoRoutes(String cityCode) {
            if (routesFailure != null) {
                throw routesFailure;
            }
            return routes.getOrDefault(cityCode, List.of());
        }

        @Override
        public List<RegionalRouteStop> tagoRouteStops(String cityCode, String routeId) {
            if (retryableFailuresBeforeSuccess.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                throw RegionalBusSourceException.retryable("resultCode=99");
            }
            if (failing.containsKey(routeId)) {
                throw failing.get(routeId);
            }
            return tagoStops.getOrDefault(routeId, List.of());
        }

        @Override
        public List<RegionalRouteStop> gyeonggiRouteStops(String routeId) {
            ggCalls.add(routeId);
            return ggStops.getOrDefault(routeId, List.of());
        }
    }
}
