package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Spy;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.testsupport.InMemoryRegionalBusRouteStopStore;
import java.time.Instant;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
    @Mock TagoBusArrivalPort tagoBusArrivalPort;
    /** 운영에서는 띠 이름이 null 아니면 한 개 이상이다. Mockito 기본값(빈 목록)이 그 자리에 끼지 않게 null 로 답한다. */
    @Mock BusApproachStopNames approachStopNames;

    @org.junit.jupiter.api.BeforeEach
    void approachStopNamesAnswerNullUnlessStubbed() {
        org.mockito.Mockito.lenient().when(approachStopNames.seoul(any(), anyInt(), any(), any())).thenReturn(null);
        org.mockito.Mockito.lenient().when(approachStopNames.gyeonggi(any(), any(), any(), any())).thenReturn(null);
    }
    /** 노선 표가 빈 조회기 — 접두사 표만 쓴다(#267 이전과 같은 동작). */
    @Spy TagoCityCodeLookup tagoCityCodeLookup = InMemoryRegionalBusRouteStopStore.prefixTableOnlyLookup();
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
        verify(tagoBusArrivalPort, never()).getArrivals(anyString(), anyString(), anyString());
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

    // ── 대기 화면용 칸(bus-boarding-flow S1): 몇 정류장 전·차량 id·띠 이름 ─────────────────────

    @Test
    void seoul_stopsAwayIsOrdMinusSectionOrder_andVehicleIdsPassThrough() {
        // N16 실응답(2026-11-10): 요청 순번 40, 첫 차 구간 순번 37 "남대문시장.회현역" → "6분38초후[3번째 전]",
        // 둘째 차 25 → "26분11초후[15번째 전]".
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(40)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 40))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "109059152", 37, "남대문시장.회현역", "6분38초후[3번째 전]",
                        "109042262", 25, "26분11초후[15번째 전]"))));
        given(approachStopNames.seoul(ROUTE, 40, 3, "남대문시장.회현역")).willReturn(List.of("가", "나", "다"));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getStopsAway1()).isEqualTo(3);
        assertThat(r.getStopsAway2()).isEqualTo(15);
        assertThat(r.getVehicleId1()).isEqualTo("109059152");
        assertThat(r.getVehicleId2()).isEqualTo("109042262");
        assertThat(r.getApproachStops1()).containsExactly("가", "나", "다");
        assertThat(r.getArrivalMsg1()).isEqualTo("6분38초후[3번째 전]");   // 기존 칸은 그대로
    }

    @Test
    void seoul_noBus_vehicleZeroMeansUnknownStopsAwayAndNoVehicle() {
        // 서울은 버스가 없으면 차량 id "0", 구간 순번 0(운행종료·출발대기 실응답).
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(10)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 10))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "0", 0, " ", "운행종료", " ", 0, "운행종료"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getStopsAway1()).isNull();
        assertThat(r.getStopsAway2()).isNull();
        assertThat(r.getVehicleId1()).isNull();
        assertThat(r.getVehicleId2()).isNull();
        verify(approachStopNames).seoul(ROUTE, 10, null, " ");
    }

    @Test
    void seoul_sectionOrderWithoutAVehicle_orVehicleWithoutASectionOrder_isUnknown() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(10)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 10))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "0", 7, "어딘가", "3분후", "v2", 0, "출발대기"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getStopsAway1()).isNull();   // 구간 순번이 남아 있어도 차량이 없으면 믿지 않는다
        assertThat(r.getStopsAway2()).isNull();   // 차량 id 가 있어도 구간 순번 0 은 위치가 아니다
        assertThat(r.getVehicleId2()).isEqualTo("v2");
    }

    @Test
    void seoul_busAtTheStopIsZero_andPastTheStopIsUnknown() {
        // N13 실응답: 요청 순번 40, 첫 차 구간 순번 40 "곧 도착"(현재 정류장 = 요청 정류장).
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(40)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 40))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "110058306", 40, "동대문역.흥인지문", "곧 도착", "v2", 44, "8분후"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getStopsAway1()).isZero();
        assertThat(r.getStopsAway2()).isNull();   // 요청 정류장을 지나친 순번
    }

    @Test
    void gyeonggi_newFields_andTheStripUsesTheFilteredStopsAway() {
        given(busRouteStopRepository.findByRouteIdAndStopId("228000184", "123000001")).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival("123000001", "228000184"))
                .willReturn(BusQueryResult.ok(List.of(new GyeonggiBusArrival(
                        "228000184", 4, 2, 2, 33, 29, 19, 4, 39, "PASS", "241001", "241002", 251, null, "수원역"))));
        given(approachStopNames.gyeonggi("228000184", "123000001", 2, "수원역")).willReturn(List.of("수원역", "나"));

        BusArrivalResponse r = service.getArrivals("123000001", "228000184").get(0);

        assertThat(r.getStopsAway1()).isEqualTo(2);
        assertThat(r.getStopsAway2()).isEqualTo(19);
        assertThat(r.getVehicleId1()).isEqualTo("241001");
        assertThat(r.getVehicleId2()).isEqualTo("241002");
        assertThat(r.getPredictTime1()).isEqualTo(251);     // 초 칸이 분과 맞으면 그 값
        assertThat(r.getPredictTime2()).isEqualTo(1740);    // 없으면 분 × 60
        assertThat(r.getArrivalMsg1()).isEqualTo("4분후[2번째 전]");   // 문구는 그대로 분 기준
        assertThat(r.getApproachStops1()).containsExactly("수원역", "나");
    }

    @Test
    void gyeonggi_secondsThatDisagreeWithTheMinutes_areNotUsed() {
        // 초 칸의 뜻은 값이 든 실응답으로 확인하지 못했다. 분과 1분 넘게 어긋나면 분을 믿는다.
        given(busRouteStopRepository.findByRouteIdAndStopId("228000184", "123000001")).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival("123000001", "228000184"))
                .willReturn(BusQueryResult.ok(List.of(new GyeonggiBusArrival(
                        "228000184", 5, 2, 2, 33, 9, 6, 4, 39, "PASS", "241001", "241002", 0, 600, null))));

        BusArrivalResponse r = service.getArrivals("123000001", "228000184").get(0);

        assertThat(r.getPredictTime1()).isEqualTo(300);   // 0초 ≠ 5분
        assertThat(r.getPredictTime2()).isEqualTo(600);   // 600초 = 9분에서 60초 차이 → 쓴다
    }

    @Test
    void gyeonggi_noBus_newFieldsAndTheStripAreNullEvenIfSourceLeavesValues() {
        given(busRouteStopRepository.findByRouteIdAndStopId("228000184", "123000001")).willReturn(List.of());
        given(gyeonggiBusArrivalPort.getArrival("123000001", "228000184"))
                .willReturn(BusQueryResult.ok(List.of(new GyeonggiBusArrival(
                        "228000184", null, 3, null, null, null, null, null, null, "WAIT", "241001", null, 99, null,
                        "수원역"))));

        BusArrivalResponse r = service.getArrivals("123000001", "228000184").get(0);

        assertThat(r.getStopsAway1()).isNull();
        assertThat(r.getVehicleId1()).isNull();
        assertThat(r.getPredictTime1()).isNull();
        // 원천에 남은 "3정류장 전"으로 띠를 만들지 않는다
        verify(approachStopNames).gyeonggi("228000184", "123000001", null, "수원역");
    }

    @Test
    void tago_stopsAwayFollowsTheSortedBuses_andHasNoStrip() {
        given(tagoBusArrivalPort.getArrivals("21", "BSB167850202", "BSB5200186000"))
                .willReturn(BusQueryResult.ok(List.of(
                        new TagoBusArrival("BSB5200186000", 600, 8, null),
                        new TagoBusArrival("BSB5200186000", 150, 2, null))));

        BusArrivalResponse r = service.getArrivals("BSB167850202", "BSB5200186000").get(0);

        assertThat(r.getStopsAway1()).isEqualTo(2);     // 먼저 오는 차
        assertThat(r.getStopsAway2()).isEqualTo(8);
        assertThat(r.getVehicleId1()).isNull();          // TAGO 는 차량 id 를 주지 않는다
        assertThat(r.getApproachStops1()).isNull();      // 현재 정류장 이름이 없어 순번을 확인할 수 없다
        org.mockito.Mockito.verifyNoInteractions(approachStopNames);
    }

    private BusRouteStop routeStop(int seq) {
        return BusRouteStop.builder().routeId(ROUTE).stopId(STOP).seq(seq).build();
    }

    // ── TAGO(서울·경기 밖) ──────────────────────────────────────────────────

    @Test
    void getArrivals_tagoId_queriesTagoOnly_sortedAndMapped() {
        // 부산 186 영도대교 실측: 늦은 버스가 먼저 왔다 — 도착 순으로 정렬해야 한다.
        given(tagoBusArrivalPort.getArrivals("21", "BSB167850202", "BSB5200186000"))
                .willReturn(BusQueryResult.ok(List.of(
                        new TagoBusArrival("BSB5200186000", 3643, 49, "저상버스"),
                        new TagoBusArrival("BSB5200186000", 1721, 25, "저상버스"))));

        List<BusArrivalResponse> result = service.getArrivals("BSB167850202", "BSB5200186000");

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.getRouteId()).isEqualTo("BSB5200186000");
            assertThat(r.getArrivalMsg1()).isEqualTo("28분후[25번째 전]");
            assertThat(r.getPredictTime1()).isEqualTo(1721);      // 초 그대로
            assertThat(r.getArrivalMsg2()).isEqualTo("60분후[49번째 전]");
            assertThat(r.getPredictTime2()).isEqualTo(3643);
            assertThat(r.getCongestionNum1()).isZero();           // TAGO 혼잡도 없음
            assertThat(r.getCongestionNum2()).isZero();
        });
        verify(busRouteStopRepository, never()).findByRouteIdAndStopId(anyString(), anyString());
        verify(gyeonggiBusArrivalPort, never()).getArrival(anyString(), anyString());
    }

    @Test
    void getArrivals_tagoOneBusSoon_secondIsNoInfo() {
        given(tagoBusArrivalPort.getArrivals("37050", "GMB119", "GMB171-110"))
                .willReturn(BusQueryResult.ok(List.of(new TagoBusArrival("GMB171-110", 45, 1, "일반차량"))));

        BusArrivalResponse r = service.getArrivals("GMB119", "GMB171-110").get(0);

        assertThat(r.getArrivalMsg1()).isEqualTo("곧 도착");
        assertThat(r.getArrivalMsg2()).isEqualTo("도착 정보 없음");
        assertThat(r.getPredictTime2()).isNull();
    }

    @Test
    void getArrivals_tagoNoBusOrFailure_returnsEmpty() {
        given(tagoBusArrivalPort.getArrivals("21", "BSB167850202", "BSB5200186000"))
                .willReturn(BusQueryResult.ok(List.of()),
                        BusQueryResult.<TagoBusArrival>limited(),
                        BusQueryResult.<TagoBusArrival>error());

        assertThat(service.getArrivals("BSB167850202", "BSB5200186000")).isEmpty();
        assertThat(service.getArrivals("BSB167850202", "BSB5200186000")).isEmpty();
        assertThat(service.getArrivals("BSB167850202", "BSB5200186000")).isEmpty();
    }

    @Test
    void getArrivals_tagoIdOutsideCityTableOrMismatched_noCallAnywhere() {
        assertThat(service.getArrivals("TSB309000122", "TSB309000001")).isEmpty();   // 표 밖(농어촌)
        assertThat(service.getArrivals("BSB167850202", "DGB3000320000")).isEmpty();  // 지역 불일치
        assertThat(service.getArrivals("BSB167850202", "228000184")).isEmpty();      // 모양 혼합

        verify(tagoBusArrivalPort, never()).getArrivals(anyString(), anyString(), anyString());
        verify(busRouteStopRepository, never()).findByRouteIdAndStopId(anyString(), anyString());
        verify(gyeonggiBusArrivalPort, never()).getArrival(anyString(), anyString());
    }

    @Test
    void getArrivals_tagoCityCodeComesFromRouteTable() {
        // #267: 접두사 표 밖(TSB 농어촌)·다도시 접두사(ADB 두 번째 도시)도 노선 표로 도시코드를 찾는다
        InMemoryRegionalBusRouteStopStore store = new InMemoryRegionalBusRouteStopStore();
        List<RegionalRouteStop> stops = List.of(new RegionalRouteStop("S", "s", 1, null, null, null, null, null));
        store.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", stops, Instant.EPOCH);
        store.replaceRoute(RegionalBusProvider.TAGO, "ADB354300207", "1", "37400", stops, Instant.EPOCH);
        TagoCityCodeLookup lookup = new TagoCityCodeLookup(store);
        lookup.refresh();
        BusArrivalQueryService withTable = new BusArrivalQueryService(
                busQueryPort, busRouteStopRepository, gyeonggiBusArrivalPort, tagoBusArrivalPort, lookup,
                approachStopNames);
        given(tagoBusArrivalPort.getArrivals(anyString(), anyString(), anyString()))
                .willReturn(BusQueryResult.ok(List.of()));

        withTable.getArrivals("TSB309000122", "TSB309000001");
        withTable.getArrivals("ADB354000033", "ADB354300207");

        verify(tagoBusArrivalPort).getArrivals("32050", "TSB309000122", "TSB309000001");
        verify(tagoBusArrivalPort).getArrivals("37400", "ADB354000033", "ADB354300207");
    }

    @Test
    void getArrivals_tagoRouteIdWithLetters_isTago() {
        // 부산 일부 노선 id 는 영문을 포함한다(BSB5201002F00). 서울·경기 경로로 새면 안 된다.
        given(tagoBusArrivalPort.getArrivals("21", "BSB167850202", "BSB5201002F00"))
                .willReturn(BusQueryResult.ok(List.of()));

        assertThat(service.getArrivals("BSB167850202", "BSB5201002F00")).isEmpty();

        verify(tagoBusArrivalPort).getArrivals("21", "BSB167850202", "BSB5201002F00");
        verify(busRouteStopRepository, never()).findByRouteIdAndStopId(anyString(), anyString());
    }

    @Test
    void seoul_endedMessageWithAVehicle_hasNoTimeStopsAwayOrVehicle() {
        // 막차가 정류장에 닿은 순간 서울은 "운행종료" 와 차량 id, 구간 순번 = 요청 순번, 몇 초를 같이 준다(2026-11-16).
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(71)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 71))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "122014120", 71, "신분당선강남역", "운행종료", "122014121", 70, "운행종료"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);

        assertThat(r.getArrivalMsg1()).isEqualTo("운행종료");
        assertThat(r.getPredictTime1()).isNull();
        assertThat(r.getPredictTime2()).isNull();
        assertThat(r.getStopsAway1()).isNull();
        assertThat(r.getVehicleId1()).isNull();
        assertThat(r.getStopsAway2()).isNull();
        assertThat(r.getVehicleId2()).isNull();
    }

    @Test
    void seoul_vehicleWithATime_keepsTheSourceSeconds() {
        given(busRouteStopRepository.findByRouteIdAndStopId(ROUTE, STOP)).willReturn(List.of(routeStop(40)));
        given(busQueryPort.getArrInfoByStop(STOP, ROUTE, 40))
                .willReturn(BusQueryResult.ok(List.of(BusArrivalInfoFixtures.arrivalAt(
                        "109059152", 37, "남대문시장.회현역", "6분38초후[3번째 전]",
                        "109042262", 25, "26분11초후[15번째 전]"))));

        BusArrivalResponse r = service.getArrivals(STOP, ROUTE).get(0);
        var source = BusArrivalInfoFixtures.arrivalAt("109059152", 37, "남대문시장.회현역", "6분38초후[3번째 전]",
                "109042262", 25, "26분11초후[15번째 전]");

        assertThat(r.getPredictTime1()).isEqualTo(source.sectionTime1()).isNotNull();
        assertThat(r.getPredictTime2()).isEqualTo(source.sectionTime2()).isNotNull();
    }
}
