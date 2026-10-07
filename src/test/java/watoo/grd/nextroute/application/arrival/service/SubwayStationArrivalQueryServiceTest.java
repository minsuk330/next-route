package watoo.grd.nextroute.application.arrival.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Direction;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Train;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader.Arrival;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubwayStationArrivalQueryServiceTest {

    private static final String GANGNAM = "1002000222";

    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final SubwayStationRepository subwayStationRepository = mock(SubwayStationRepository.class);
    private final SubwayStationArrivalQueryService service =
            new SubwayStationArrivalQueryService(arrivalReader, subwayStationRepository);

    @Test
    @DisplayName("역 ID 의 노선으로 묻고 방향별로 묶는다 — 방향 안 순서는 reader 순서(초 없는 열차 뒤) 그대로")
    void groupsByDirectionKeepingReaderOrder() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(
                arrival("2231", "내선", "성수", 96),
                arrival("2401", "외선", "성수", 120),
                arrival("2233", "내선", "성수", 300),
                arrival("2235", "내선", "성수", null)));

        SubwayStationArrivalsResponse response = service.getArrivals(GANGNAM);

        assertThat(response.stationId()).isEqualTo(GANGNAM);
        assertThat(response.lineId()).isEqualTo("1002");
        Direction inner = response.directions().get(0);
        assertThat(inner.direction()).isEqualTo("내선");
        assertThat(inner.trains()).extracting(Train::trainNo).containsExactly("2231", "2233", "2235");
        assertThat(response.directions().get(1).trains()).extracting(Train::trainNo).containsExactly("2401");
    }

    @Test
    @DisplayName("label 은 행선지 최빈값 + ' 방면', 동률이면 먼저 오는 열차의 행선지")
    void labelIsMostCommonDestination() {
        givenStation("1077000687", "1077");
        given(arrivalReader.arrivals("1077", "1077000687", null)).willReturn(List.of(
                arrival("1", "상행", "신사", 60),
                arrival("2", "상행", "강남", 120),
                arrival("3", "상행", "강남", 180),
                arrival("4", "하행", "광교", 60),
                arrival("5", "하행", "정자", 120)));

        List<Direction> directions = service.getArrivals("1077000687").directions();

        assertThat(directions).extracting(Direction::label).containsExactly("강남 방면", "광교 방면");
    }

    @Test
    @DisplayName("두 방향 label 이 같으면(2호선 양쪽 성수행) 방향 원문을 쓴다")
    void fallsBackToDirectionWhenLabelsCollide() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(
                arrival("2401", "외선", "성수", 96),
                arrival("2231", "내선", "성수", 176)));

        assertThat(service.getArrivals(GANGNAM).directions())
                .extracting(Direction::label).containsExactly("내선", "외선");
    }

    @Test
    @DisplayName("방향 순서는 도착 순서와 무관하게 고정 — 새로고침해도 탭 자리가 안 바뀐다")
    void directionOrderIsStable() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null))
                .willReturn(List.of(arrival("a", "하행", "x", 60), arrival("b", "상행", "y", 120)))
                .willReturn(List.of(arrival("b", "상행", "y", 60), arrival("a", "하행", "x", 120)));

        assertThat(service.getArrivals(GANGNAM).directions())
                .extracting(Direction::direction).containsExactly("상행", "하행");
        assertThat(service.getArrivals(GANGNAM).directions())
                .extracting(Direction::direction).containsExactly("상행", "하행");
    }

    @Test
    @DisplayName("방향이 비면 null 로 묶어 맨 뒤에 — 행선지도 없으면 '방향 정보 없음'")
    void blankDirectionGoesLastWithPlaceholderLabel() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(
                arrival("1", " ", "", 60),
                arrival("2", "내선", "", 120)));

        List<Direction> directions = service.getArrivals(GANGNAM).directions();

        assertThat(directions).extracting(Direction::direction).containsExactly("내선", null);
        assertThat(directions).extracting(Direction::label).containsExactly("내선", "방향 정보 없음");
    }

    @Test
    @DisplayName("방향이 비어도 행선지가 있으면 행선지가 label — 앱 기존 동작과 같다")
    void blankDirectionStillUsesDestination() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null))
                .willReturn(List.of(arrival("1", null, "성수", 60)));

        assertThat(service.getArrivals(GANGNAM).directions())
                .extracting(Direction::label).containsExactly("성수 방면");
    }

    @Test
    @DisplayName("없는 역 → SUBWAY_STATION_NOT_FOUND, 실시간은 묻지 않는다")
    void unknownStationIsNotFound() {
        given(subwayStationRepository.findByStationId("9999")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getArrivals("9999"))
                .isInstanceOfSatisfying(BusinessException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));
        verify(arrivalReader, never()).arrivals(any(), any(), any());
    }

    @Test
    @DisplayName("운행 정보가 없으면 빈 방향 목록 — 오류가 아니다")
    void noTrainsIsEmptyDirections() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of());

        assertThat(service.getArrivals(GANGNAM).directions()).isEmpty();
    }

    @Test
    @DisplayName("남은 초가 없는 열차에 거리를 실어 준다")
    void carriesStationsAway() {
        givenStation(GANGNAM, "1002");
        Arrival far = new Arrival(train("7", "내선", "성수", "99", "[3]번째 전역"), null);
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(far));

        Train only = service.getArrivals(GANGNAM).directions().get(0).trains().get(0);

        assertThat(only.arrivalSeconds()).isNull();
        assertThat(only.stationsAway()).isEqualTo(3);
        assertThat(only.currentMessage()).isEqualTo("[3]번째 전역");
        assertThat(only.trainType()).isEqualTo("일반");
    }

    private void givenStation(String stationId, String lineId) {
        SubwayStation station = mock(SubwayStation.class);
        given(station.getStationId()).willReturn(stationId);
        given(station.getLineId()).willReturn(lineId);
        given(subwayStationRepository.findByStationId(stationId)).willReturn(Optional.of(station));
    }

    private Arrival arrival(String trainNo, String direction, String destination, Integer seconds) {
        return new Arrival(train(trainNo, direction, destination, "99", "전역 출발"), seconds);
    }

    private SubwayRealtimeTrain train(String trainNo, String direction, String destination,
                                      String arrivalCode, String message) {
        return SubwayRealtimeTrain.builder()
                .trainNo(trainNo)
                .direction(direction)
                .destinationName(destination)
                .arrivalCode(arrivalCode)
                .currentMessage(message)
                .trainType("일반")
                .build();
    }
}
