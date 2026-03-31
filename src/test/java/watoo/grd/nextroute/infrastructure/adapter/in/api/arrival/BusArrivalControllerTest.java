package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(BusArrivalController.class)
class BusArrivalControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GetBusArrivalUseCase getBusArrivalUseCase;

    @Test
    void getArrivals_returns200WithList() throws Exception {
        given(getBusArrivalUseCase.getArrivals("22000")).willReturn(List.of(
                BusArrivalResponse.builder()
                        .routeId("100100001")
                        .arrivalMsg1("3분 후")
                        .predictTime1(180)
                        .build()
        ));

        mockMvc.perform(get("/api/arrivals/bus/22000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].routeId").value("100100001"))
                .andExpect(jsonPath("$[0].predictTime1").value(180));
    }

    @Test
    void getArrivals_emptyList_returns200() throws Exception {
        given(getBusArrivalUseCase.getArrivals("99999")).willReturn(List.of());

        mockMvc.perform(get("/api/arrivals/bus/99999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
