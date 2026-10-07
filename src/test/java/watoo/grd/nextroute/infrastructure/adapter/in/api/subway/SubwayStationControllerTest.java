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
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayStationArrivalsUseCase;
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

    @Test
    void returnsDirectionsWithTrains() throws Exception {
        given(useCase.getArrivals("1002000222")).willReturn(new SubwayStationArrivalsResponse(
                "1002000222", "1002", List.of(new Direction("내선", "내선", List.of(
                        new Train("2231", "성수", "일반", null, 1, "전역 출발"))))));

        mockMvc.perform(get("/api/subway/stations/1002000222/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stationId").value("1002000222"))
                .andExpect(jsonPath("$.lineId").value("1002"))
                .andExpect(jsonPath("$.directions[0].direction").value("내선"))
                .andExpect(jsonPath("$.directions[0].label").value("내선"))
                .andExpect(jsonPath("$.directions[0].trains[0].trainNo").value("2231"))
                .andExpect(jsonPath("$.directions[0].trains[0].stationsAway").value(1))
                .andExpect(jsonPath("$.directions[0].trains[0].currentMessage").value("전역 출발"));
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
}
