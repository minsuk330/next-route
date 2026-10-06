package watoo.grd.nextroute.application.route.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TAGO 도시코드 조회 계약(#267): 노선 표 우선, 없으면 접두사 표, 표를 못 읽으면 쥐고 있던 값.
 */
class TagoCityCodeLookupTest {

    private static final Instant AT = Instant.parse("2026-10-01T00:00:00Z");
    private static final List<RegionalRouteStop> STOPS =
            List.of(new RegionalRouteStop("S1", "s", 1, null, null, null, null, null));

    private final InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();
    private final TagoCityCodeLookup lookup = new TagoCityCodeLookup(store);

    @Test
    @DisplayName("표가 비어 있으면 지금처럼 접두사 표로 찾는다")
    void emptyTableFallsBackToPrefix() {
        lookup.refresh();

        assertThat(lookup.cityCodeOfRoute("BSB5200186000")).contains("21");
        assertThat(lookup.cityCodeOfRoute("TSB309000001")).isEmpty();
        assertThat(lookup.cityCodeOfRoute(null)).isEmpty();
    }

    @Test
    @DisplayName("다도시 접두사: 표의 노선 값이 접두사 표보다 앞선다(ADB 의 두 번째 도시, TSB)")
    void tableWinsOverPrefix() {
        store.replaceRoute(RegionalBusProvider.TAGO, "ADB354300207", "1", "37400", STOPS, AT);
        store.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", STOPS, AT);
        lookup.refresh();

        assertThat(lookup.cityCodeOfRoute("ADB354300207")).contains("37400");   // 접두사 표는 안동 37040
        assertThat(lookup.cityCodeOfRoute("ADB999")).contains("37040");          // 표에 없는 노선 → 접두사
        assertThat(lookup.cityCodeOfRoute("TSB309000001")).contains("32050");
    }

    @Test
    @DisplayName("경기 행은 TAGO 도시코드 대상이 아니다")
    void gyeonggiRowsIgnored() {
        store.replaceRoute(RegionalBusProvider.GG, "233000031", "7770", "31010", STOPS, AT);
        lookup.refresh();

        assertThat(lookup.cityCodeOfRoute("233000031")).isEmpty();
        assertThat(lookup.isRealtimePrefix("233")).isFalse();
    }

    @Test
    @DisplayName("정류장만 보는 자리: 표의 접두사 ∪ 접두사 표")
    void realtimePrefix() {
        store.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", STOPS, AT);
        lookup.refresh();

        assertThat(lookup.isRealtimePrefix("TSB")).isTrue();
        assertThat(lookup.isRealtimePrefix("BSB")).isTrue();
        assertThat(lookup.isRealtimePrefix("CCB")).isFalse();
        assertThat(lookup.isRealtimePrefix(null)).isFalse();
    }

    @Test
    @DisplayName("표를 못 읽으면(V56 미배치 등) 쥐고 있던 스냅샷을 그대로 쓴다")
    void refreshFailureKeepsPrevious() {
        AtomicBoolean broken = new AtomicBoolean(false);
        InMemoryRegionalBusRouteStopStore flaky = new InMemoryRegionalBusRouteStopStore() {
            @Override
            public synchronized Map<String, String> tagoRouteCityCodes() {
                if (broken.get()) {
                    throw new IllegalStateException("relation \"regional_bus_route_stop\" does not exist");
                }
                return super.tagoRouteCityCodes();
            }
        };
        flaky.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", STOPS, AT);
        TagoCityCodeLookup flakyLookup = new TagoCityCodeLookup(flaky);
        flakyLookup.refresh();

        broken.set(true);
        flakyLookup.refresh();

        assertThat(flakyLookup.cityCodeOfRoute("TSB309000001")).contains("32050");
    }

    @Test
    @DisplayName("BusRealtimeStopIds: 표로 알게 된 접두사의 정류장도 실시간 id 를 낸다")
    void realtimeStopIdsUseLookup() {
        store.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", STOPS, AT);
        lookup.refresh();
        BusRealtimeStopIds ids = new BusRealtimeStopIds(lookup);

        assertThat(ids.of("1:BS_TAGO_TSB309000122")).contains("TSB309000122");
        assertThat(ids.of("1:BS_TAGO_CCB100")).isEmpty();
        assertThat(ids.of("1:BS_TAGO_GGB277101594")).contains("277101594");
    }
}
