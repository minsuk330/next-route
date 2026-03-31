package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.arrival.dto.SubwayArrivalResponse;
import watoo.grd.nextroute.application.arrival.port.in.GetSubwayArrivalUseCase;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SubwayArrivalController.class)
class SubwayArrivalControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GetSubwayArrivalUseCase getSubwayArrivalUseCase;

    @Test
    void getArrivals_returns200WithList() throws Exception {
        given(getSubwayArrivalUseCase.getArrivals("1002000233")).willReturn(List.of(
                SubwayArrivalResponse.builder()
                        .lineId("1002").direction("상행")
                        .arrivalSeconds(60).currentMessage("1정거장 전")
                        .destinationName("성수행").build()
        ));

        mockMvc.perform(get("/api/arrivals/subway/1002000233"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].lineId").value("1002"))
                .andExpect(jsonPath("$[0].arrivalSeconds").value(60));
    }
}
