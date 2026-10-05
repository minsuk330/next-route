package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BusArrivalQueryServiceTest {

    static final String STOP = "22000";
    static final String ROUTE = "100100118";

    @Mock SearchTimeBusQueryPort busQueryPort;
    @Mock BusRouteStopRepository busRouteStopRepository;
    @Mock GyeonggiBusArrivalPort gyeonggiBusArrivalPort;
    @InjectMocks BusArrivalQueryService service;

    @Test
    void getArrivals_singleMapping_queriesByOrdAndMaps() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 3))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalInfo(
                        "v1", "12가1234", "3분 후", "v2", "12가5678", "8분 후"))));

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRouteId()).isEqualTo(ROUTE);
        assertThat(result.get(0).getArrivalMsg1()).isEqualTo("3분 후");
        verify(gyeonggiBusArrivalPort, never()).getArrival(anyString(), anyString());
    }

    @Test
    void getArrivals_cachedResult_mapsSame() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 3))
                .willReturn(BusQueryResult.cached(List.of(BusArrivalInfoFixtures.arrivalInfo(
                        "v1", "12가1234", "3분 후", "v2", "12가5678", "8분 후"))));

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getArrivalMsg1()).isEqualTo("3분 후");
    }

    @Test
    void getArrivals_okButEmpty_returnsEmpty() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 3))
                .willReturn(BusQueryResult.ok(List.of()));

        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
    }

    @Test
    void getArrivals_blocked_returnsEmpty() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 3))
                .willReturn(BusQueryResult.blocked());

        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
    }

    @Test
    void getArrivals_limited_returnsEmpty() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 3))
                .willReturn(BusQueryResult.<BusArrivalInfo>limited());

        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
    }

    @Test
    void getArrivals_noSeoulMapping_queriesGyeonggiAndMapsToSeoulSemantics() {
        // 잠실역.잠실대교남단 123000001 / 5600 228000184 — 서울 카탈로그엔 없다.
        given(busRouteStopRepository.findByRouteIdAndStopId("228000184", "123000001")).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival("123000001", "228000184"))
                .willReturn(BusQueryResult.ok(List.of(
                        new GyeonggiBusArrival("228000184", 4, 2, 2, 33, 29, 19, 4, 39, "PASS"))));

        List<BusArrivalResponse> result = service.getArrivals("123000001", "228000184");

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.getRouteId()).isEqualTo("228000184");
            assertThat(r.getArrivalMsg1()).isEqualTo("4분후[2번째 전]");
            assertThat(r.getPredictTime1()).isEqualTo(240);       // 분 → 초(서울 traTime 과 같은 단위)
            assertThat(r.getCongestionNum1()).isEqualTo(4);       // 경기 2(보통) → 서울 4(보통)
            assertThat(r.getArrivalMsg2()).isEqualTo("29분후[19번째 전]");
            assertThat(r.getPredictTime2()).isEqualTo(1740);
            assertThat(r.getCongestionNum2()).isEqualTo(6);       // 경기 4(매우혼잡) → 서울 6
        });
        verify(busQueryPort, never()).getArrInfoByStop(anyString(), anyString(), anyInt());
    }

    @Test
    void getArrivals_gyeonggiSoonAndNoBus_messages() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival(STOP, ROUTE))
                .willReturn(BusQueryResult.ok(List.of(
                        new GyeonggiBusArrival(ROUTE, 1, 1, null, null, null, null, null, null, "STOP"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getArrivalMsg1()).isEqualTo("곧 도착");
        assertThat(r.getCongestionNum1()).isZero();               // 정보 없음
        assertThat(r.getArrivalMsg2()).isEqualTo("운행종료");      // 두 번째 버스 없음 + flag STOP
        assertThat(r.getPredictTime2()).isNull();
    }

    @Test
    void getArrivals_gyeonggiUnknownFlagWithoutBus_noInfoMessage() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival(STOP, ROUTE))
                .willReturn(BusQueryResult.ok(List.of(
                        new GyeonggiBusArrival(ROUTE, null, null, null, null, null, null, null, null, "PASS"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getArrivalMsg1()).isEqualTo("도착 정보 없음");
        assertThat(r.getPredictTime1()).isNull();
    }

    @Test
    void getArrivals_gyeonggiNoResult_returnsEmpty() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival(STOP, ROUTE)).willReturn(BusQueryResult.ok(List.of()));

        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
    }

    @Test
    void getArrivals_gyeonggiBlockedOrError_returnsEmpty() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival(STOP, ROUTE))
                .willReturn(BusQueryResult.<GyeonggiBusArrival>blocked(),
                        BusQueryResult.<GyeonggiBusArrival>error());

        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
        assertThat(service.getArrivals(STOP, ROUTE)).isEmpty();
    }

    @Test
    void getArrivals_ambiguousLoopMapping_emptyAndNoApiCall() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP))
                .willReturn(List.of(routeStop(3), routeStop(31)));

        List<BusArrivalResponse> result = service.getArrivals(STOP, ROUTE);

        assertThat(result).isEmpty();
        verify(busQueryPort, never()).getArrInfoByStop(anyString(), anyString(), anyInt());
        verify(gyeonggiBusArrivalPort, never()).getArrival(anyString(), anyString());
    }

    private BusRouteStop routeStop(int seq) {
        return BusRouteStop.builder().routeId(ROUTE).stopId(STOP).seq(seq).build();
    }
}
