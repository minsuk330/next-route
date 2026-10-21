package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse.Station;
import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures.stationId;

/** 하차역 고르기의 열차별 정차(subway-express PR-S3). 남은 역 계산은 운영 fixture 패턴으로 실제로 돈다. */
class SubwayStationsAheadQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-21T11:00:00Z");

    private final SubwayStationRepository repository = mock(SubwayStationRepository.class);
    private final SubwayLinePatternService patterns = mock(SubwayLinePatternService.class);
    private final SubwayLineStationIndex index = mock(SubwayLineStationIndex.class);
    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final SubwayTrainLogPort trainLogPort = mock(SubwayTrainLogPort.class);
    private final SubwayExpressTrackingProperties tracking = SubwayExpressFixtures.tracking(false);
    private SubwayStationsAheadQueryService service;

    @BeforeEach
    void setUp() {
        given(patterns.patterns(any(), any())).willAnswer(inv -> directed(inv.getArgument(0), inv.getArgument(1)));
        given(index.candidates(any())).willAnswer(inv -> SubwayPatternFixtures.STATIONS.get(inv.<String>getArgument(0)));
        service = new SubwayStationsAheadQueryService(new SubwayStationsAheadService(repository, patterns, index),
                SubwayExpressFixtures.judge(tracking), arrivalReader, trainLogPort, new ServiceDateResolver(),
                Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));
    }

    private static List<DirectedRailPattern> directed(String lineId, String direction) {
        List<DirectedRailPattern> out = new ArrayList<>();
        for (OtpRailRoute route : SubwayRoutePatternAssembler.selectRoutes(SubwayPatternFixtures.ROUTES, lineId,
                direction)) {
            out.addAll(SubwayRoutePatternAssembler.orient(route, SubwayPatternFixtures.PATTERNS.get(route.gtfsId()),
                    direction));
        }
        return out;
    }

    private void board(String lineId, String name) {
        SubwayStation station = mock(SubwayStation.class);
        given(station.getStationId()).willReturn(stationId(lineId, name));
        given(station.getLineId()).willReturn(lineId);
        given(station.getStationName()).willReturn(name);
        given(repository.findByStationId(stationId(lineId, name))).willReturn(Optional.of(station));
    }

    private void rows(String lineId, String trainNo, String direction, String destination, String... types) {
        List<SubwayRealtimeTrain> rows = new ArrayList<>();
        for (String type : types) {
            rows.add(SubwayRealtimeTrain.builder().lineId(lineId).trainNo(trainNo).direction(direction)
                    .destinationName(destination).trainType(type).build());
        }
        given(arrivalReader.trainRows(lineId, trainNo)).willReturn(rows);
    }

    private static Station station(SubwayStationsAheadResponse r, String lineId, String name) {
        String id = stationId(lineId, name);
        return r.stations().stream().filter(s -> id.equals(s.stationId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("trainNo 를 안 주면 예전 그대로 — train·stopsHere·expressStopsAway 모두 null, 실시간을 읽지 않는다")
    void withoutTrain() {
        board("1009", "가양");

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1009", "가양"), "상행", null, null);

        assertThat(r.train()).isNull();
        assertThat(r.stations()).allSatisfy(s -> {
            assertThat(s.stopsHere()).isNull();
            assertThat(s.expressStopsAway()).isNull();
        });
        verify(arrivalReader, never()).trainRows(any(), any());
    }

    @Test
    @DisplayName("9호선 급행(가양 승차): 지나치는 역은 stopsHere=false, 서는 역만 센 expressStopsAway, stopsAway 는 완행 그대로")
    void line9Express() {
        board("1009", "가양");
        rows("1009", "9600", "상행", "중앙보훈병원", "일반", "급행");

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1009", "가양"), "상행", null, "9600");

        assertThat(r.train()).isEqualTo(new SubwayStationsAheadResponse.TrainInfo(TrainKind.EXPRESS, "급행",
                "1009-express", true));
        assertThat(station(r, "1009", "증미")).extracting(Station::stopsHere, Station::expressStopsAway,
                Station::stopsAway).containsExactly(false, null, 1);
        assertThat(station(r, "1009", "등촌").stopsHere()).isFalse();
        assertThat(station(r, "1009", "염창")).extracting(Station::stopsHere, Station::expressStopsAway,
                Station::stopsAway).containsExactly(true, 1, 3);
        assertThat(station(r, "1009", "선유도").stopsHere()).isFalse();
        assertThat(station(r, "1009", "당산")).extracting(Station::stopsHere, Station::expressStopsAway)
                .containsExactly(true, 2);
        assertThat(station(r, "1009", "중앙보훈병원").stopsHere()).isTrue();
    }

    @Test
    @DisplayName("종착 범위: 종합운동장행 급행이면 그 뒤 역(석촌·중앙보훈병원)은 stopsHere=false")
    void terminalRange() {
        board("1009", "가양");
        rows("1009", "9600", "상행", "종합운동장", "급행");

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1009", "가양"), "상행", null, "9600");

        assertThat(station(r, "1009", "종합운동장").stopsHere()).isTrue();
        assertThat(station(r, "1009", "석촌").stopsHere()).isFalse();
        assertThat(station(r, "1009", "중앙보훈병원")).extracting(Station::stopsHere, Station::expressStopsAway)
                .containsExactly(false, null);
    }

    @Test
    @DisplayName("4호선 급행은 실시간 근거가 없어도 번호(44xx)로 — 행선지는 요청값. 사당 승차: 한대앞 통과, 안산 정차")
    void line4ExpressByTrainNoOnly() {
        board("1004", "사당");

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1004", "사당"), "하행", "오이도", "4405");

        assertThat(r.train().kind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(r.train().serviceId()).isEqualTo("1004-ansan");
        assertThat(station(r, "1004", "금정").stopsHere()).isTrue();
        assertThat(station(r, "1004", "수리산").stopsHere()).isFalse();
        assertThat(station(r, "1004", "한대앞").stopsHere()).isFalse();
        assertThat(station(r, "1004", "안산").stopsHere()).isTrue();
    }

    @Test
    @DisplayName("ITX 는 위치 기록의 원시 directAt 9 로 안다 — 정차역은 모름(null)")
    void itxFromPosition() {
        board("1067", "청량리");
        given(trainLogPort.lastSeen(any())).willReturn(Optional.of(new SubwayTrainObservation("1067080117", "회기",
                SubwayTrainStatus.values()[0], NOW, "1067080140", SubwayExpressType.UNKNOWN, "9")));

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1067", "청량리"), "하행", null, "2029");

        assertThat(r.train()).isEqualTo(new SubwayStationsAheadResponse.TrainInfo(TrainKind.ITX, "ITX-청춘", null,
                null));
        assertThat(r.stations()).allSatisfy(s -> assertThat(s.stopsHere()).isNull());
    }

    @Test
    @DisplayName("일반 열차: train 은 NORMAL·승차역 정차, 역마다 정차 칸은 null(완행은 다 선다)")
    void normalTrain() {
        board("1009", "가양");
        rows("1009", "9100", "상행", "중앙보훈병원", "일반");

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1009", "가양"), "상행", null, "9100");

        assertThat(r.train()).isEqualTo(new SubwayStationsAheadResponse.TrainInfo(TrainKind.NORMAL, "일반", null,
                true));
        assertThat(r.stations()).allSatisfy(s -> assertThat(s.stopsHere()).isNull());
    }

    @Test
    @DisplayName("열차 정보는 덧붙이는 값 — 실시간 조회가 깨져도 열차 없이 목록은 준다")
    void trainFailureKeepsList() {
        board("1009", "가양");
        given(arrivalReader.trainRows(any(), any())).willThrow(new IllegalStateException("redis down"));

        SubwayStationsAheadResponse r = service.getStationsAhead(stationId("1009", "가양"), "상행", null, "9600");

        assertThat(r.train()).isNull();
        assertThat(r.stations()).isNotEmpty();
    }
}
