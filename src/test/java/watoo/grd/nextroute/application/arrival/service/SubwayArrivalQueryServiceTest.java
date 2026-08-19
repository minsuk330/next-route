package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubwayArrivalQueryServiceTest {

    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final SubwayDataService subwayDataService = mock(SubwayDataService.class);
    private final SubwayArrivalQueryService service =
            new SubwayArrivalQueryService(arrivalReader, subwayDataService);

    @Test
    void resolveStation_selectsTheRequestedInternalLine() {
        NearbySubwayStationProjection lineNine = station("923", "신논현", "1009");
        NearbySubwayStationProjection sinbundang = station("D07", "신논현", "1077");
        given(subwayDataService.findNearbyStations(37.5045, 127.0250, 50, 5))
                .willReturn(List.of(lineNine, sinbundang));

        assertThat(service.resolveStation(37.5045, 127.0250, "1077"))
                .contains(new SubwayArrivalQueryService.ResolvedStation(
                        "D07", "신논현", "1077"));
    }

    @Test
    @DisplayName("남은 초가 없어도 거리를 실어 내려 준다 — 초가 없다고 열차가 사라지면 안 된다")
    void carriesDistanceWhenEtaIsMissing() {
        givenStation();
        given(arrivalReader.arrivals("1075", "1075075212", null)).willReturn(List.of(
                arrival("6135", null, "5", "전역 도착"),
                arrival("6137", null, "99", "[3]번째 전역")));

        List<SubwayArrivalResponse> responses =
                service.getArrivals(37.5271, 127.0402, null, "1075");

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).getArrivalSeconds()).isNull();
        assertThat(responses.get(0).getStationsAway()).isEqualTo(1);
        assertThat(responses.get(0).getCurrentMessage()).isEqualTo("전역 도착");
        assertThat(responses.get(1).getStationsAway()).isEqualTo(3);
    }

    @Test
    @DisplayName("조회역에 도착한 열차는 거리가 0 이다")
    void marksZeroDistanceAtQueriedStation() {
        givenStation();
        given(arrivalReader.arrivals("1075", "1075075212", null))
                .willReturn(List.of(arrival("6542", null, "1", "압구정로데오 도착")));

        assertThat(service.getArrivals(37.5271, 127.0402, null, "1075"))
                .singleElement()
                .extracting(SubwayArrivalResponse::getStationsAway)
                .isEqualTo(0);
    }

    @Test
    @DisplayName("50m 에서 후보가 없으면 반경을 넓혀 찾는다 — 눈앞의 역을 두고 '정보 없음' 이 되면 안 된다")
    void expandsRadiusWhenNothingIsFoundNearby() {
        NearbySubwayStationProjection station = station("1075075212", "압구정로데오", "1075");
        given(subwayDataService.findNearbyStations(37.5271, 127.0402, 150.0, 5))
                .willReturn(List.of(station));

        assertThat(service.resolveStation(37.5271, 127.0402, "1075"))
                .contains(new SubwayArrivalQueryService.ResolvedStation(
                        "1075075212", "압구정로데오", "1075"));
    }

    @Test
    @DisplayName("50m 에서 잡히면 더 넓히지 않는다 — 좁은 반경의 판단 근거를 그대로 둔다")
    void doesNotExpandWhenTheNearestRadiusAnswers() {
        givenStation();

        service.resolveStation(37.5271, 127.0402, "1075");

        verify(subwayDataService, never())
                .findNearbyStations(anyDouble(), anyDouble(), eq(100.0), anyInt());
    }

    @Test
    @DisplayName("한계 반경까지 후보가 없으면 빈 값 — 없는 역을 지어내지 않는다")
    void stopsExpandingAtTheMaxRadius() {
        assertThat(service.resolveStation(37.5271, 127.0402, "1075")).isEmpty();

        verify(subwayDataService, never())
                .findNearbyStations(anyDouble(), anyDouble(), eq(350.0), anyInt());
    }

    @Test
    @DisplayName("넓힌 반경에서도 요청한 호선을 고른다 — 확대가 노선 선택 규칙을 흐리지 않는다")
    void keepsLineSelectionAtTheExpandedRadius() {
        NearbySubwayStationProjection lineNine = station("923", "신논현", "1009");
        NearbySubwayStationProjection sinbundang = station("D07", "신논현", "1077");
        given(subwayDataService.findNearbyStations(37.5045, 127.0250, 200.0, 5))
                .willReturn(List.of(lineNine, sinbundang));

        assertThat(service.resolveStation(37.5045, 127.0250, "1077"))
                .contains(new SubwayArrivalQueryService.ResolvedStation(
                        "D07", "신논현", "1077"));
    }

    private void givenStation() {
        // 스터빙을 먼저 끝내 두고 넘긴다. given(...) 인자 안에서 또 스터빙하면 Mockito 가
        // 바깥 스터빙이 안 끝난 것으로 보고 UnfinishedStubbingException 을 던진다.
        NearbySubwayStationProjection station = station("1075075212", "압구정로데오", "1075");
        given(subwayDataService.findNearbyStations(37.5271, 127.0402, 50, 5))
                .willReturn(List.of(station));
    }

    private SubwayRealtimeArrivalReader.Arrival arrival(String trainNo, Integer seconds,
                                                        String arrivalCode, String currentMessage) {
        return new SubwayRealtimeArrivalReader.Arrival(
                SubwayRealtimeTrain.builder()
                        .trainNo(trainNo)
                        .lineId("1075")
                        .direction("하행")
                        .arrivalCode(arrivalCode)
                        .currentMessage(currentMessage)
                        .destinationName("고색")
                        .trainType("일반")
                        .build(),
                seconds);
    }

    private NearbySubwayStationProjection station(String stationId, String name, String lineId) {
        NearbySubwayStationProjection station = mock(NearbySubwayStationProjection.class);
        given(station.getStationId()).willReturn(stationId);
        given(station.getStationName()).willReturn(name);
        given(station.getLineId()).willReturn(lineId);
        return station;
    }
}
