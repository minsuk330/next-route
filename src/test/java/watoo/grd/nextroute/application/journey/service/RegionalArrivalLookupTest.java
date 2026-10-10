package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.port.out.BusApiQuotaPort;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@DisplayName("경기·TAGO 내 버스 도착(#264 PR-4)")
class RegionalArrivalLookupTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");

    private final GyeonggiBusArrivalPort gyeonggi = mock(GyeonggiBusArrivalPort.class);
    private final TagoBusArrivalPort tago = mock(TagoBusArrivalPort.class);
    private final TagoCityCodeLookup cityCodes = mock(TagoCityCodeLookup.class);
    private final BusApiQuotaPort quota = mock(BusApiQuotaPort.class);
    private final RegionalArrivalLookup lookup = new RegionalArrivalLookup(gyeonggi, tago, cityCodes, quota,
            new JourneyRerouteProperties(), Clock.fixed(NOW, ZoneOffset.UTC));

    {
        given(quota.tryAcquire(anyString(), anyInt())).willReturn(true);
    }

    @Test
    @DisplayName("남은 순번이 기대값이거나 하나 적은 차만 내 차다 — 내 뒤의 차(더 먼 차)는 받지 않는다")
    void picksMyBusByStopsAway() {
        // 1번째 차: 3정류장 전 2분, 2번째 차: 6정류장 전 9분.
        given(gyeonggi.getArrival("S", "R")).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 3, null, null, 9, 6, null, null, null))));

        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3).at()).isEqualTo(NOW.plusSeconds(120));
        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 4).at()).isEqualTo(NOW.plusSeconds(120));
        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 2).failureReason())
                .as("기대 2인데 3정류장 전 차는 내 뒤의 차다").isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("다음 버스(기다리는 단계, #333): 몇 정류장 전이든 가장 이른 도착")
    void nextArrivalIsEarliest() {
        given(gyeonggi.getArrival("S", "R")).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 9, 6, null, null, 2, 1, null, null, null))));

        assertThat(lookup.nextArrival(RegionalBusProvider.GG, "R", "S").at()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("다음 버스: 오는 차가 없으면 no-realtime")
    void nextArrivalWithoutVehicles() {
        given(gyeonggi.getArrival("S", "R")).willReturn(BusQueryResult.ok(List.of()));

        assertThat(lookup.nextArrival(RegionalBusProvider.GG, "R", "S").failureReason()).isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("같은 정류장·노선은 짧게 캐시한다 — 여정끼리 호출을 나눠 쓴다")
    void cachesShortly() {
        given(gyeonggi.getArrival("S", "R")).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 3, null, null, null, null, null, null, null))));

        lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);
        lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);

        verify(gyeonggi, times(1)).getArrival("S", "R");
    }

    @Test
    @DisplayName("재탐색 전용 할당량이 다 떨어지면 묻지 않는다")
    void quotaExhausted() {
        given(quota.tryAcquire(anyString(), anyInt())).willReturn(false);

        RegionalArrivalLookup.Lookup result = lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);

        assertThat(result.quotaExhausted()).isTrue();
        verify(gyeonggi, times(0)).getArrival(anyString(), anyString());
    }

    @Test
    @DisplayName("TAGO 노선의 도시코드를 모르면 조회하지 못한다")
    void tagoWithoutCityCode() {
        given(cityCodes.cityCodeOfRoute("DJB1")).willReturn(Optional.empty());

        assertThat(lookup.arrivalAt(RegionalBusProvider.TAGO, "DJB1", "S", 3).failureReason())
                .isEqualTo("arrival-blocked");
    }
}
