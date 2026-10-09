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

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Departed;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainEvent;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainObservation;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.dto.TrainIdentity;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.service.ServiceDateResolver;
import watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup;
import watoo.grd.nextroute.application.subway.port.out.SubwayArrivalDepartureLogPort;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.DepartureSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SubwayStationArrivalQueryServiceTest {

    private static final String GANGNAM = "1002000222";

    private final SubwayRealtimeArrivalReader arrivalReader = mock(SubwayRealtimeArrivalReader.class);
    private final SubwayStationRepository subwayStationRepository = mock(SubwayStationRepository.class);
    private final SubwayTrainLogPort trainLogPort = mock(SubwayTrainLogPort.class);
    private final SubwayArrivalDepartureLogPort arrivalDepartureLog = mock(SubwayArrivalDepartureLogPort.class);

    private SubwayDepartureLookup lookup() {
        return new SubwayDepartureLookup(trainLogPort, arrivalDepartureLog, new ServiceDateResolver());
    }
    private static final Instant NOW = Instant.parse("2026-10-07T07:00:00Z"); // 16:00 KST
    private final SubwayStationArrivalQueryService service = new SubwayStationArrivalQueryService(
            arrivalReader, subwayStationRepository, trainLogPort, lookup(),
            Clock.fixed(NOW, ZoneId.of("Asia/Seoul")));

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

    @Test
    @DisplayName("#299 방향마다 10분 안에 떠난 열차를 최근 순 최대 3대 — 같은 열차는 한 번")
    void departedTrainsAreRecentFirstAndCapped() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(arrival("2231", "내선", "성수", 96)));
        given(trainLogPort.findDepartures(LocalDate.of(2026, 10, 7), "1002", "내선", GANGNAM,
                NOW.minusSeconds(600), NOW)).willReturn(List.of(
                departure("2301", 540), departure("2302", 420), departure("2303", 300),
                departure("2304", 60), departure("2304", 120)));

        List<Departed> departed = service.getArrivals(GANGNAM).directions().get(0).departed();

        assertThat(departed).extracting(Departed::trainNo).containsExactly("2304", "2303", "2302");
        assertThat(departed.get(0).departedAt().toInstant()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    @DisplayName("#299 행선지 이름·열차 종류·지금 위치를 붙인다 — 떠난 뒤 관측이 없으면 위치는 null")
    void departedCarriesDestinationTypeAndPosition() {
        givenStation(GANGNAM, "1002");
        SubwayStation terminal = mock(SubwayStation.class);
        given(terminal.getStationName()).willReturn("성수");
        given(subwayStationRepository.findByStationId("1002000211")).willReturn(Optional.of(terminal));
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of());
        given(trainLogPort.findDepartures(any(), eq("1002"), eq("내선"), eq(GANGNAM), any(), any()))
                .willReturn(List.of(departure("2306", 120), departure("2305", 400)));
        TrainIdentity moved = new TrainIdentity(LocalDate.of(2026, 10, 7), "1002", "내선", "2306");
        given(trainLogPort.lastSeen(moved)).willReturn(Optional.of(new SubwayTrainObservation(
                "1002000223", "역삼", SubwayTrainStatus.values()[0], NOW.minusSeconds(30), "1002000211",
                SubwayExpressType.NORMAL)));
        TrainIdentity stale = new TrainIdentity(LocalDate.of(2026, 10, 7), "1002", "내선", "2305");
        given(trainLogPort.lastSeen(stale)).willReturn(Optional.of(new SubwayTrainObservation(
                GANGNAM, "강남", SubwayTrainStatus.values()[0], NOW.minusSeconds(900), "1002000211",
                SubwayExpressType.NORMAL)));

        Direction inner = service.getArrivals(GANGNAM).directions().get(0);

        assertThat(inner.direction()).isEqualTo("내선");
        assertThat(inner.trains()).isEmpty();
        assertThat(inner.label()).isEqualTo("성수 방면");
        assertThat(inner.departed().get(0)).extracting(Departed::destinationName, Departed::trainType,
                Departed::currentStationName).containsExactly("성수", "일반", "역삼");
        assertThat(inner.departed().get(1).currentStationName()).isNull();
    }

    @Test
    @DisplayName("#299 출발 로그를 못 읽어도 곧 오는 열차는 그대로 — 떠난 열차만 빈다")
    void logFailureKeepsUpcomingTrains() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of(arrival("2231", "내선", "성수", 96)));
        given(trainLogPort.findDepartures(any(), any(), any(), any(), any(), any()))
                .willThrow(new IllegalStateException("redis down"));

        Direction inner = service.getArrivals(GANGNAM).directions().get(0);

        assertThat(inner.trains()).hasSize(1);
        assertThat(inner.departed()).isEmpty();
    }

    @Test
    @DisplayName("#299 서비스일 경계(04:00) 직후면 전날 운행 로그도 본다")
    void looksAtPreviousServiceDateAcrossBoundary() {
        Instant justAfter = Instant.parse("2026-10-06T19:05:00Z"); // 04:05 KST
        SubwayStationArrivalQueryService early = new SubwayStationArrivalQueryService(
                arrivalReader, subwayStationRepository, trainLogPort, lookup(),
                Clock.fixed(justAfter, ZoneId.of("Asia/Seoul")));
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of());

        early.getArrivals(GANGNAM);

        verify(trainLogPort).findDepartures(eq(LocalDate.of(2026, 10, 7)), eq("1002"), eq("내선"), eq(GANGNAM), any(), any());
        verify(trainLogPort).findDepartures(eq(LocalDate.of(2026, 10, 6)), eq("1002"), eq("내선"), eq(GANGNAM), any(), any());
    }

    @Test
    @DisplayName("#317 위치 출발 사건이 없으면 도착 API 기록으로 채운다 — 행선지·종류는 그 기록, 원천 ARRIVAL")
    void departedFallsBackToArrivalFeed() {
        givenStation("1075000210", "1075");
        given(arrivalReader.arrivals("1075", "1075000210", null)).willReturn(List.of());
        given(arrivalDepartureLog.find(eq(LocalDate.of(2026, 10, 7)), eq("1075"), eq("상행"), eq("1075000210"),
                any(), any())).willReturn(List.of(
                new SubwayArrivalDepartureLogPort.Row("K4021", "청량리", "급행", NOW.minusSeconds(90)),
                new SubwayArrivalDepartureLogPort.Row("K4019", "왕십리", "일반", NOW.minusSeconds(400))));

        Direction up = service.getArrivals("1075000210").directions().get(0);

        assertThat(up.direction()).isEqualTo("상행");
        assertThat(up.departed()).extracting(Departed::trainNo).containsExactly("K4021", "K4019");
        assertThat(up.departed().get(0)).extracting(Departed::destinationName, Departed::trainType, Departed::source)
                .containsExactly("청량리", "급행", DepartureSource.ARRIVAL);
        assertThat(up.label()).isEqualTo("청량리 방면");
    }

    @Test
    @DisplayName("#317 같은 열차가 두 원천에 있으면 위치 원천")
    void positionWinsOverArrivalFeed() {
        givenStation(GANGNAM, "1002");
        given(arrivalReader.arrivals("1002", GANGNAM, null)).willReturn(List.of());
        given(trainLogPort.findDepartures(any(), eq("1002"), eq("내선"), eq(GANGNAM), any(), any()))
                .willReturn(List.of(departure("2304", 60)));
        given(arrivalDepartureLog.find(any(), eq("1002"), eq("내선"), eq(GANGNAM), any(), any()))
                .willReturn(List.of(new SubwayArrivalDepartureLogPort.Row("2304", "성수", "일반", NOW.minusSeconds(30))));

        List<Departed> departed = service.getArrivals(GANGNAM).directions().get(0).departed();

        assertThat(departed).hasSize(1);
        assertThat(departed.get(0).source()).isEqualTo(DepartureSource.POSITION);
        assertThat(departed.get(0).departedAt().toInstant()).isEqualTo(NOW.minusSeconds(60));
    }

    @Test
    void trainTypeFollowsArrivalVocabulary() {
        assertThat(SubwayStationArrivalQueryService.trainType(SubwayExpressType.NORMAL)).isEqualTo("일반");
        assertThat(SubwayStationArrivalQueryService.trainType(SubwayExpressType.EXPRESS)).isEqualTo("급행");
        assertThat(SubwayStationArrivalQueryService.trainType(SubwayExpressType.UNKNOWN)).isNull();
    }

    private SubwayTrainLogPort.TrainDeparture departure(String trainNo, int secondsAgo) {
        Instant mid = NOW.minusSeconds(secondsAgo);
        return new SubwayTrainLogPort.TrainDeparture(trainNo, new SubwayTrainEvent(
                SubwayTrainEvent.Type.DEPARTED, GANGNAM, "강남", mid.minusSeconds(75), mid.plusSeconds(75),
                SubwayTrainEvent.Quality.CORROBORATED, "1002000211", SubwayExpressType.NORMAL));
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
