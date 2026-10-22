package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class SubwayLineStationIndexTest {

    private final SubwayStationRepository repository = mock(SubwayStationRepository.class);
    private final SubwayLineStationIndex index = new SubwayLineStationIndex(repository);

    @Test
    @DisplayName("같은 이름·300m 안의 다른 노선만 환승 — 양평(5호선↔경의중앙 55km), 신촌(2호선↔경의중앙 약 670m)은 아니다")
    void transfersNeedSameNameAndProximity() {
        // 스터빙을 먼저 끝내 두고 넘긴다. given(...) 인자 안에서 mock 을 스터빙하면 UnfinishedStubbingException.
        List<SubwayStation> stations = List.of(
                station("1005000525", "양평", "1005", "5호선", 37.5256, 126.8864),
                station("1063080312", "양평", "1063", "경의중앙선", 37.4927, 127.4922),
                station("1002000240", "신촌", "1002", "2호선", 37.5552, 126.9369),
                station("1063080314", "신촌", "1063", "경의중앙선", 37.5598, 126.9425),
                station("1002000222", "강남", "1002", "2호선", 37.4979, 127.0276),
                station("1077000687", "강남", "1077", "신분당선", 37.4966, 127.0284));
        given(repository.findAll()).willReturn(stations);

        assertThat(index.transfers("양평", 37.5256, 126.8864, "1005")).isEmpty();
        assertThat(index.transfers("신촌", 37.5552, 126.9369, "1002")).isEmpty();
        assertThat(index.transfers("강남", 37.4979, 127.0276, "1002"))
                .containsExactly(new SubwayLineStationIndex.Transfer("1077", "신분당선"));
    }

    @Test
    @DisplayName("역 ID 로 마스터 이름을 준다 — 괄호가 이름의 일부인 역은 그대로, 모르는 역은 empty")
    void stationNameById() {
        List<SubwayStation> stations = List.of(
                station("1007000732", "논현", "1007", "7호선", 37.5110, 127.0214),
                station("1007000736", "총신대입구(이수)", "1007", "7호선", 37.4866, 126.9819));
        given(repository.findAll()).willReturn(stations);

        assertThat(index.stationName("1007000732")).contains("논현");
        assertThat(index.stationName("1007000736")).contains("총신대입구(이수)");
        assertThat(index.stationName("9999999999")).isEmpty();
        assertThat(index.stationName(null)).isEmpty();
    }

    @Test
    @DisplayName("#510 경로 역의 환승 — 같은 노선 마스터 좌표로 재고, 모르는 노선·좌표 없음은 empty, 환승 없으면 빈 목록")
    void transfersAtRouteStop() {
        // 종합운동장 2호선↔9호선 마스터 거리 약 221m. 단계 좌표(GTFS)를 9호선 마스터에서 동쪽으로 150m 비켜 두면
        // 그 좌표로는 2호선까지 300m 를 넘는다 — 같은 노선 마스터 좌표로 재야 환승이 나온다
        List<SubwayStation> stations = List.of(
                station("1009000930", "종합운동장", "1009", "9호선", 37.5114097871778, 127.076324485585),
                station("1002000218", "종합운동장", "1002", "2호선", 37.5111446632705, 127.073849447402),
                station("1009000929", "봉은사", "1009", "9호선", 37.5142554489848, 127.060233935114));
        given(repository.findAll()).willReturn(stations);

        assertThat(index.transfersAt("종합운동장(9호선)", 37.51141, 127.07802, "1009"))
                .contains(List.of(new SubwayLineStationIndex.Transfer("1002", "2호선")));
        assertThat(index.transfersAt("봉은사", 37.51426, 127.06023, "1009")).contains(List.of());
        assertThat(index.transfersAt("종합운동장", 37.51141, 127.07802, "9999")).isEmpty();
        assertThat(index.transfersAt("종합운동장", null, 127.07802, "1009")).isEmpty();
        assertThat(index.transfersAt("종합운동장", 37.51141, 127.07802, null)).isEmpty();
    }

    @Test
    @DisplayName("역 마스터는 한 번만 읽는다")
    void loadsOnce() {
        List<SubwayStation> stations = List.of(station("1", "강남", "1002", "2호선", 37.49, 127.02));
        given(repository.findAll()).willReturn(stations);

        index.candidates("1002");
        index.candidates("1002");

        verify(repository, times(1)).findAll();
        assertThat(index.candidates("1002")).hasSize(1);
    }

    @Test
    @DisplayName("빈 역 마스터는 남기지 않는다 — 적재 전 첫 호출 뒤에 적재되면 다음 호출이 읽는다(급행 카탈로그 재시도의 전제)")
    void emptyMasterIsNotCached() {
        List<SubwayStation> stations = List.of(station("1", "강남", "1002", "2호선", 37.49, 127.02));
        given(repository.findAll()).willReturn(List.of()).willReturn(stations);

        assertThat(index.candidates("1002")).isEmpty();
        assertThat(index.candidates("1002")).hasSize(1);
        index.candidates("1002");

        verify(repository, times(2)).findAll();
    }

    static SubwayStation station(String id, String name, String lineId, String lineName, double lat, double lon) {
        SubwayStation s = mock(SubwayStation.class);
        given(s.getStationId()).willReturn(id);
        given(s.getStationName()).willReturn(name);
        given(s.getLineId()).willReturn(lineId);
        given(s.getLineName()).willReturn(lineName);
        given(s.getLatitude()).willReturn(lat);
        given(s.getLongitude()).willReturn(lon);
        return s;
    }
}
