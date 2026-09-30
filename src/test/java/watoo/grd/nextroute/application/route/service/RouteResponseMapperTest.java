package watoo.grd.nextroute.application.route.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.v2.RouteSearchResultV2;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.infrastructure.adapter.out.api.otp.OtpPlanConnectionMapper;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** OTP fixture → 중립 RoutingResult → legacy/v2 응답 전체 체인 계약. */
class RouteResponseMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OtpPlanConnectionMapper otpMapper = new OtpPlanConnectionMapper();
    private final BusStopRepository busStopRepo = mock(BusStopRepository.class);
    private final BusRouteRepository busRouteRepo = mock(BusRouteRepository.class);
    private final SubwayStationRepository subwayRepo = mock(SubwayStationRepository.class);
    private final OtpSeoulIdentityResolver resolver =
            new OtpSeoulIdentityResolver(busStopRepo, busRouteRepo, subwayRepo);
    private final LegacyRouteResponseMapper legacyMapper = new LegacyRouteResponseMapper(resolver);
    private final RouteSearchV2Mapper v2Mapper = new RouteSearchV2Mapper();

    /** legacy 서울 id 필드에서 프론트가 실시간을 호출하려면 stop/route 둘 다 DB 존재해야 한다. */
    private void busMappable() {
        when(busStopRepo.existsByStopId(anyString())).thenReturn(true);
        when(busRouteRepo.existsByRouteId(anyString())).thenReturn(true);
    }

    /** "1:BS_TAGO_SEB100000004" → "100000004"(9자리). resolver 와 같은 strip 규칙. */
    private static String stripId(String gtfsId) {
        return gtfsId.replaceFirst("^\\d+:B[SR]_TAGO_SEB", "");
    }

    private RoutingResult routingResult() throws IOException {
        var root = om.readTree(new ClassPathResource("otp/planconnection-success.json").getContentAsByteArray());
        var versions = new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-abc");
        return (RoutingResult) otpMapper.map(root, Instant.parse("2026-09-07T23:00:00Z"), versions);
    }

    @Test
    @DisplayName("legacy: trafficType/정류장/요금 sentinel(-1). OTP fare 없음 → payment=-1(0 아님)")
    void toLegacy() throws IOException {
        RouteSearchResult legacy = legacyMapper.toLegacy(routingResult());
        assertThat(legacy.paths()).isNotEmpty();
        PathResult path = legacy.paths().get(0);
        // OTP fare 없음 → UNKNOWN → -1 sentinel
        assertThat(path.info().payment()).isEqualTo(LegacyRouteResponseMapper.PAYMENT_UNKNOWN);
        assertThat(path.info().transferCount()).isEqualTo(1);
        assertThat(legacy.busCount()).isGreaterThan(0);
        // 첫 leg WALK(3), 둘째 BUS(2)
        assertThat(path.subPaths().get(0).trafficType()).isEqualTo(3);
        SubPathResult bus = path.subPaths().get(1);
        assertThat(bus.trafficType()).isEqualTo(2);
        assertThat(bus.stations()).isNotEmpty();
        assertThat(bus.stations().get(0).stationID()).startsWith("1:");   // 원천 gtfsId 보존
        // 채우지 못하는 필드는 null(가짜값 금지)
        assertThat(bus.polyline()).isNull();
        assertThat(path.info().mapObj()).isNull();
    }

    @Test
    @DisplayName("v2: provider/graphVersion + nullable 요금(UNKNOWN→null) + coverage/geometryQuality 노출")
    void toV2() throws IOException {
        RouteSearchResultV2 v2 = v2Mapper.toV2(routingResult());
        assertThat(v2.provider()).isEqualTo("OTP");
        assertThat(v2.graphVersion()).isEqualTo("g-abc");
        var path = v2.paths().get(0);
        assertThat(path.fare().availability()).isEqualTo("UNKNOWN");
        assertThat(path.fare().amount()).isNull();       // UNKNOWN → null(0 아님)
        assertThat(path.navigationSupported()).isFalse();
        var bus = path.subPaths().get(1);
        assertThat(bus.mode()).isEqualTo("BUS");
        assertThat(bus.geometryQuality()).isEqualTo("STREET_FALLBACK");
        assertThat(bus.missing()).contains("REALTIME_ID", "DIRECTION");
    }

    @Test
    @DisplayName("legacy 버스: 매핑되면 startLocalStationID=stop_id, lane.busLocalBlID=route_id 채움(프론트 실시간 호출용)")
    void busSeoulIdFilled() throws IOException {
        busMappable();
        RouteSearchResult legacy = legacyMapper.toLegacy(routingResult());
        SubPathResult bus = legacy.paths().get(0).subPaths().get(1);
        assertThat(bus.trafficType()).isEqualTo(2);

        // 승차 정류장 = stations[0], 하차 = stations[last]. strip 한 9자리가 legacy 필드로.
        String boardingStop = stripId(bus.stations().get(0).stationID());
        String alightStop = stripId(bus.stations().get(bus.stations().size() - 1).stationID());
        assertThat(bus.startLocalStationID()).isEqualTo(boardingStop);
        assertThat(bus.endLocalStationID()).isEqualTo(alightStop);

        assertThat(bus.lanes()).hasSize(1);
        assertThat(bus.lanes().get(0).busLocalBlID()).matches("\\d{9}");   // route_id
        assertThat(bus.lanes().get(0).busNo()).isNotBlank();               // 버스번호 표시
        // 지하철 아니므로 startId/startArsID 는 여전히 null(가짜값 금지)
        assertThat(bus.startId()).isNull();
        assertThat(bus.startArsID()).isNull();
    }

    @Test
    @DisplayName("legacy 지하철: id 안 채움(좌표 기반 실시간), 좌표는 유지")
    void subwayNoIdCoordsKept() throws IOException {
        busMappable();   // 버스 repo 매핑돼도 지하철 leg 은 mode 가드로 id 안 붙어야 함
        RouteSearchResult legacy = legacyMapper.toLegacy(routingResult());
        // fixture edges[2] = WALK/BUS/WALK/SUBWAY(서울4호선)/WALK → paths[2].subPaths[3]
        SubPathResult subway = legacy.paths().get(2).subPaths().get(3);
        assertThat(subway.trafficType()).isEqualTo(1);          // 지하철
        // lanes 는 여기서 안 본다 - 노선 표시색(PR #210)이 지하철에도 lane 을 만들기 시작했고,
        // 그 계약은 LegacyRouteResponseMapperTest 가 본다. 이 테스트가 지키는 것은 아래 "id 가짜값 금지" 다.
        assertThat(subway.startLocalStationID()).isNull();      // 좌표 기반이라 id 불필요
        assertThat(subway.endLocalStationID()).isNull();
        assertThat(subway.startId()).isNull();
        // 좌표는 유지(프론트가 /api/arrivals/subway?lat&lon 호출)
        assertThat(subway.startX()).isNotNull();
        assertThat(subway.startY()).isNotNull();
        assertThat(subway.stations()).isNotEmpty();
    }

    @Test
    @DisplayName("legacy 버스: DB 미매핑이면 id null(가짜값 금지), 단 lane 은 버스번호로 표시 유지")
    void busUnmappedKeepsDisplay() throws IOException {
        // busMappable() 미호출 → repo exists=false → resolveBus empty
        RouteSearchResult legacy = legacyMapper.toLegacy(routingResult());
        SubPathResult bus = legacy.paths().get(0).subPaths().get(1);
        assertThat(bus.startLocalStationID()).isNull();
        assertThat(bus.endLocalStationID()).isNull();
        assertThat(bus.lanes()).hasSize(1);
        assertThat(bus.lanes().get(0).busLocalBlID()).isNull();   // 실시간 호출 불가
        assertThat(bus.lanes().get(0).busNo()).isNotBlank();      // 표시는 유지
    }
}
