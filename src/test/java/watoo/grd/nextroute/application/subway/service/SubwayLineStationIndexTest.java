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
    @DisplayName("역 마스터는 한 번만 읽는다")
    void loadsOnce() {
        List<SubwayStation> stations = List.of(station("1", "강남", "1002", "2호선", 37.49, 127.02));
        given(repository.findAll()).willReturn(stations);

        index.candidates("1002");
        index.candidates("1002");

        verify(repository, times(1)).findAll();
        assertThat(index.candidates("1002")).hasSize(1);
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
