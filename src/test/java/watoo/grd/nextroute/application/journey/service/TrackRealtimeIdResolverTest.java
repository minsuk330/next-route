package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.TrackRealtimeIdResolver.BusStopIds;
import watoo.grd.nextroute.application.journey.service.TrackRealtimeIdResolver.SubwayStationIds;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.subway.service.SubwayPatternStationMatcher.Candidate;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class TrackRealtimeIdResolverTest {

    private final BusStopRepository busStopRepository = mock(BusStopRepository.class);
    private final BusRouteRepository busRouteRepository = mock(BusRouteRepository.class);
    private final SubwayLineStationIndex stationIndex = mock(SubwayLineStationIndex.class);
    private final TrackRealtimeIdResolver resolver = new TrackRealtimeIdResolver(
            new OtpSeoulIdentityResolver(busStopRepository, busRouteRepository,
                    mock(SubwayStationRepository.class), mock(TagoCityCodeLookup.class)),
            busStopRepository, stationIndex);

    @Test
    @DisplayName("서울 정류장: DB 에 있으면 9자리 id + ARS, 없으면 null — 가짜 id 를 주지 않는다")
    void seoulBusStop() {
        BusStop stop = mock(BusStop.class);
        given(stop.getArsId()).willReturn("22009");
        given(busStopRepository.existsByStopId("121000091")).willReturn(true);
        given(busStopRepository.findByStopId("121000091")).willReturn(Optional.of(stop));

        assertThat(resolver.busStop("1:BS_TAGO_SEB121000091")).isEqualTo(new BusStopIds("121000091", "22009"));
        assertThat(resolver.busStop("1:BS_TAGO_SEB999999999")).isEqualTo(new BusStopIds(null, null));
    }

    @Test
    @DisplayName("경기는 GBIS 9자리, 그 밖은 TAGO 접두사 포함 — 버스 도착 API 가 이 모양으로 원천을 고른다")
    void gyeonggiAndTagoStops() {
        assertThat(resolver.busStop("1:BS_TAGO_GGB121000541").arrivalStopId()).isEqualTo("121000541");
        assertThat(resolver.busStop("1:BS_TAGO_ICB121000541").arrivalStopId()).isEqualTo("ICB121000541");
        assertThat(resolver.busStop("1:BS_TAGO_GGB121000541").arsId()).isNull();
        assertThat(resolver.busStop("1:RS_ACC1_S-1-0222")).isEqualTo(new BusStopIds(null, null));
    }

    @Test
    @DisplayName("노선: 방향 접미(_U/_D) 제거, 경기 마을버스 BR_3100_, 서울은 DB 확인")
    void busRoutes() {
        given(busRouteRepository.existsByRouteId("100100118")).willReturn(true);

        assertThat(resolver.busRoute("1:BR_TAGO_SEB100100118")).isEqualTo("100100118");
        assertThat(resolver.busRoute("1:BR_TAGO_SEB100100999")).isNull();
        assertThat(resolver.busRoute("1:BR_TAGO_GGB234000026_U")).isEqualTo("234000026");
        assertThat(resolver.busRoute("1:BR_3100_241005010")).isEqualTo("241005010");
        assertThat(resolver.busRoute("1:BR_TAGO_BSB5201002F00")).isEqualTo("BSB5201002F00");
    }

    @Test
    @DisplayName("지하철: 노선 이름으로 호선을 알고 그 호선 역에 잇는다, 호선이 둘이면 두 줄")
    void subwayPerLine() {
        TransitStop stop = new TransitStop("1:RS_X", "강남", 37.4979, 127.0276, RoutingMode.SUBWAY);
        given(stationIndex.candidates("1002")).willReturn(List.of(new Candidate("1002000222", "강남", 37.4979, 127.0276)));
        given(stationIndex.candidates("1077")).willReturn(List.of(new Candidate("1077000687", "강남", 37.4966, 127.0284)));
        given(stationIndex.lineName("1002")).willReturn("2호선");
        given(stationIndex.lineName("1077")).willReturn("신분당선");

        assertThat(resolver.subway(stop, List.of("서울2호선", "신분당선"))).containsExactly(
                new SubwayStationIds("1002000222", "1002", "2호선"),
                new SubwayStationIds("1077000687", "1077", "신분당선"));
    }

    @Test
    @DisplayName("지하철: 명시표 밖 노선(인천2호선·지방 도시철도)이면 전부 null 인 한 줄 — 2호선으로 오인하지 않는다")
    void unknownLineIsOneEmptyRow() {
        TransitStop stop = new TransitStop("1:RS_Y", "인천시청(인천2호선)", 37.45, 126.70, RoutingMode.SUBWAY);

        assertThat(resolver.subway(stop, List.of("인천2호선"))).containsExactly(new SubwayStationIds(null, null, null));
    }

    @Test
    @DisplayName("지하철: 호선은 아는데 역 마스터에 이을 역이 없으면 역 id 만 null")
    void knownLineWithoutStation() {
        TransitStop stop = new TransitStop("1:RS_Z", "진접", 37.72, 127.20, RoutingMode.SUBWAY);
        given(stationIndex.candidates("1004")).willReturn(List.of(new Candidate("1004000409", "불암산", 37.67, 127.079)));
        given(stationIndex.lineName("1004")).willReturn("4호선");

        assertThat(resolver.subway(stop, List.of("서울4호선"))).containsExactly(new SubwayStationIds(null, "1004", "4호선"));
    }
}
