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
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 3, null, null, 9, 6, null, null, null))));

        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3).at()).isEqualTo(NOW.plusSeconds(120));
        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 4).at()).isEqualTo(NOW.plusSeconds(120));
        assertThat(lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 2).failureReason())
                .as("기대 2인데 3정류장 전 차는 내 뒤의 차다").isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("다음 버스(기다리는 단계, #333): 몇 정류장 전이든 가장 이른 도착")
    void nextArrivalIsEarliest() {
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 9, 6, null, null, 2, 1, null, null, null))));

        assertThat(lookup.nextArrival(RegionalBusProvider.GG, "R", "S").at()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("갈아탈 차: 준비 시각 전에 지나가는 차는 건너뛰고 그 뒤 첫 차")
    void boardableSkipsEarlierBus() {
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 1, null, null, 9, 6, null, null, null))));

        assertThat(lookup.boardableAt(RegionalBusProvider.GG, "R", "S", NOW.plusSeconds(300)).at())
                .isEqualTo(NOW.plusSeconds(540));
        assertThat(lookup.boardableAt(RegionalBusProvider.GG, "R", "S", NOW.plusSeconds(120)).at())
                .as("준비 시각과 같은 차는 탈 수 있다").isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    @DisplayName("갈아탈 차: 응답의 차가 전부 준비 시각 전이면 놓침이 아니라 no-realtime")
    void boardableWithoutLaterBus() {
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 1, null, null, 9, 6, null, null, null))));

        assertThat(lookup.boardableAt(RegionalBusProvider.GG, "R", "S", NOW.plusSeconds(600)).failureReason())
                .isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("지역 구간 판별: 서울 노선으로 풀렸거나 원천 정류장이 없으면 아니다")
    void providerOf() {
        assertThat(RegionalArrivalLookup.providerOf(null, "234001243", "101000002")).isEqualTo(RegionalBusProvider.GG);
        assertThat(RegionalArrivalLookup.providerOf(" ", "BSB5202029000", "BSB1")).isEqualTo(RegionalBusProvider.TAGO);
        assertThat(RegionalArrivalLookup.providerOf("100100224", "234001243", "101000002")).isNull();
        assertThat(RegionalArrivalLookup.providerOf(null, "234001243", " ")).isNull();
        assertThat(RegionalArrivalLookup.providerOf(null, "100100224", "101000002")).isNull();
        assertThat(RegionalArrivalLookup.providerOf(null, null, "101000002")).isNull();
    }

    @Test
    @DisplayName("다음 버스: 오는 차가 없으면 no-realtime")
    void nextArrivalWithoutVehicles() {
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of()));

        assertThat(lookup.nextArrival(RegionalBusProvider.GG, "R", "S").failureReason()).isEqualTo("no-realtime");
    }

    @Test
    @DisplayName("같은 정류장·노선은 짧게 캐시한다 — 여정끼리 호출을 나눠 쓴다")
    void cachesShortly() {
        given(gyeonggi.getArrival("S", "R", false)).willReturn(BusQueryResult.ok(List.of(
                new GyeonggiBusArrival("R", 2, 3, null, null, null, null, null, null, null))));

        lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);
        lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);

        verify(gyeonggi, times(1)).getArrival("S", "R", false);
    }

    @Test
    @DisplayName("재탐색 전용 할당량이 다 떨어지면 묻지 않는다")
    void quotaExhausted() {
        given(quota.tryAcquire(anyString(), anyInt())).willReturn(false);

        RegionalArrivalLookup.Lookup result = lookup.arrivalAt(RegionalBusProvider.GG, "R", "S", 3);

        assertThat(result.quotaExhausted()).isTrue();
        verify(gyeonggi, times(0)).getArrival(anyString(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("TAGO 노선의 도시코드를 모르면 조회하지 못한다")
    void tagoWithoutCityCode() {
        given(cityCodes.cityCodeOfRoute("DJB1")).willReturn(Optional.empty());

        assertThat(lookup.arrivalAt(RegionalBusProvider.TAGO, "DJB1", "S", 3).failureReason())
                .isEqualTo("arrival-blocked");
    }
}
