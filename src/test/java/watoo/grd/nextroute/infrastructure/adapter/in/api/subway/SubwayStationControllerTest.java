package watoo.grd.nextroute.infrastructure.adapter.in.api.subway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Direction;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.Train;
import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationsAheadUseCase;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.SubwayErrorCode;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SubwayStationController.class)
@AutoConfigureMockMvc(addFilters = false)
class SubwayStationControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GetSubwayStationArrivalsUseCase useCase;
    @MockBean GetSubwayStationsAheadUseCase aheadUseCase;

    @Test
    void returnsDirectionsWithTrains() throws Exception {
        given(useCase.getArrivals("1002000222")).willReturn(new SubwayStationArrivalsResponse(
                "1002000222", "1002", List.of(new Direction("내선", "내선", List.of(
                        new Train("2231", "성수", "일반", null, 1, "전역 출발", "1002000223", "역삼",
                                SubwayStationArrivalsResponse.TrainStatus.DEPARTED,
                                java.time.OffsetDateTime.parse("2026-10-07T15:59:20+09:00"),
                                SubwayStationArrivalsResponse.PositionSource.POSITION),
                        new Train("2233", "성수", "일반", 300, null, "5분 후", null, null, null, null, null)),
                        List.of(new SubwayStationArrivalsResponse.Departed("2229", "성수", "일반",
                                java.time.OffsetDateTime.parse("2026-10-07T15:53:40+09:00"), "교대"))))));

        mockMvc.perform(get("/api/subway/stations/1002000222/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("1002000222"))
                .andExpect(jsonPath("$.lineId").value("1002"))
                .andExpect(jsonPath("$.directions[0].direction").value("내선"))
                .andExpect(jsonPath("$.directions[0].label").value("내선"))
                .andExpect(jsonPath("$.directions[0].trains[0].trainNo").value("2231"))
                .andExpect(jsonPath("$.directions[0].trains[0].stationsAway").value(1))
                .andExpect(jsonPath("$.directions[0].trains[0].currentMessage").value("전역 출발"))
                // #317 위치 필드 — 이름이 앱 계약이다
                .andExpect(jsonPath("$.directions[0].trains[0].currentStationId").value("1002000223"))
                .andExpect(jsonPath("$.directions[0].trains[0].currentStationName").value("역삼"))
                .andExpect(jsonPath("$.directions[0].trains[0].status").value("DEPARTED"))
                .andExpect(jsonPath("$.directions[0].trains[0].observedAt").value("2026-10-07T15:59:20+09:00"))
                .andExpect(jsonPath("$.directions[0].trains[0].positionSource").value("POSITION"))
                // 모르면 키는 있고 값이 null — 구 앱은 모르는 키를 무시한다
                .andExpect(jsonPath("$.directions[0].trains[1].status").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.directions[0].trains[1].currentStationId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.directions[0].departed[0].trainNo").value("2229"))
                .andExpect(jsonPath("$.directions[0].departed[0].departedAt").value("2026-10-07T15:53:40+09:00"))
                .andExpect(jsonPath("$.directions[0].departed[0].currentStationName").value("교대"));
    }

    @Test
    void emptyDirectionsIs200() throws Exception {
        given(useCase.getArrivals("1002000222"))
                .willReturn(new SubwayStationArrivalsResponse("1002000222", "1002", List.of()));

        mockMvc.perform(get("/api/subway/stations/1002000222/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.directions").isEmpty());
    }

    @Test
    void unknownStationIs404WithCode() throws Exception {
        given(useCase.getArrivals("9999"))
                .willThrow(new BusinessException(SubwayErrorCode.SUBWAY_STATION_NOT_FOUND));

        mockMvc.perform(get("/api/subway/stations/9999/arrivals"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SUBWAY_STATION_NOT_FOUND"));
    }

    @Test
    void stationsAheadReturnsContract() throws Exception {
        given(aheadUseCase.getStationsAhead("1005000548", "하행", null)).willReturn(new SubwayStationsAheadResponse(
                "1005", "1005000548", "강동", "하행", false, SubwayStationsAheadResponse.EndReason.BRANCH,
                List.of(new SubwayStationsAheadResponse.Branch("마천", "둔촌동",
                        SubwayStationsAheadResponse.EndReason.TERMINAL)),
                List.of(new SubwayStationsAheadResponse.Station("1005000549", "둔촌동", 1, 2, List.of(),
                        "1:RS_ACC1_S-5-0549", "1:RR_ACC1_S-5-1D")),
                "1:RS_ACC1_S-5-0548"));

        mockMvc.perform(get("/api/subway/stations/1005000548/stations-ahead").param("direction", "하행"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.endReason").value("BRANCH"))
                .andExpect(jsonPath("$.destinationApplied").value(false))
                .andExpect(jsonPath("$.branches[0].destinationName").value("마천"))
                .andExpect(jsonPath("$.branches[0].kind").value("TERMINAL"))
                .andExpect(jsonPath("$.boardGtfsStopId").value("1:RS_ACC1_S-5-0548"))
                .andExpect(jsonPath("$.stations[0].gtfsStopId").value("1:RS_ACC1_S-5-0549"))
                .andExpect(jsonPath("$.stations[0].routeGtfsId").value("1:RR_ACC1_S-5-1D"));
    }

    @Test
    void stationsAheadRequiresDirection() throws Exception {
        mockMvc.perform(get("/api/subway/stations/1005000548/stations-ahead"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void stationsAheadErrorsCarryCode() throws Exception {
        given(aheadUseCase.getStationsAhead("1077000687", "하행", "광교"))
                .willThrow(new BusinessException(SubwayErrorCode.SUBWAY_ROUTE_UNAVAILABLE));

        mockMvc.perform(get("/api/subway/stations/1077000687/stations-ahead")
                        .param("direction", "하행").param("destination", "광교"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SUBWAY_ROUTE_UNAVAILABLE"));
    }
}
