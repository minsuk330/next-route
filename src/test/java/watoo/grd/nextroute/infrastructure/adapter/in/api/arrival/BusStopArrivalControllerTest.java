package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals.Status;
import watoo.grd.nextroute.application.arrival.port.in.GetBusStopArrivalsUseCase;

/** #577 응답 계약: 칸 이름, null 유지, 묶음 순서와 status, 10곳 초과 400. */
@WebMvcTest(BusStopArrivalController.class)
@AutoConfigureMockMvc(addFilters = false)
class BusStopArrivalControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean GetBusStopArrivalsUseCase useCase;

    private static BusStopArrivalResponse row() {
        return BusStopArrivalResponse.builder()
                .routeId("100100070").routeName("441").routeType(3).routeTypeSource("SEOUL").routeTypeName("간선")
                .direction("신림동").arrivalMsg1("2분후[1번째 전]").predictTime1(120).congestionNum1(3).stopsAway1(1)
                .vehicleId1("111033115").arrivalMsg2("운행종료")
                .build();
    }

    @Test
    void 정류장_하나는_노선_배열을_그대로_준다() throws Exception {
        given(useCase.getStopArrivals("121000009")).willReturn(List.of(row()));

        mockMvc.perform(get("/api/bus/stops/121000009/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].routeId").value("100100070"))
                .andExpect(jsonPath("$[0].routeName").value("441"))
                .andExpect(jsonPath("$[0].routeType").value(3))
                .andExpect(jsonPath("$[0].routeTypeSource").value("SEOUL"))
                .andExpect(jsonPath("$[0].routeTypeName").value("간선"))
                .andExpect(jsonPath("$[0].direction").value("신림동"))
                .andExpect(jsonPath("$[0].predictTime1").value(120))
                .andExpect(jsonPath("$[0].stopsAway1").value(1))
                .andExpect(jsonPath("$[0].vehicleId1").value("111033115"))
                .andExpect(jsonPath("$[0].arrivalMsg2").value("운행종료"))
                .andExpect(jsonPath("$[0].predictTime2").doesNotExist());
    }

    @Test
    void 모르는_정류장은_빈_배열() throws Exception {
        given(useCase.getStopArrivals("199999999")).willReturn(List.of());

        mockMvc.perform(get("/api/bus/stops/199999999/arrivals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void 여러_정류장은_요청_순서대로_status_와_함께() throws Exception {
        given(useCase.getStopsArrivals(List.of("121000009", "BSB199080301"))).willReturn(List.of(
                new StopArrivals("121000009", Status.OK, List.of(row())),
                new StopArrivals("BSB199080301", Status.LIMITED, List.of())));

        mockMvc.perform(get("/api/bus/stops/arrivals").param("stopIds", "121000009,BSB199080301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stopId").value("121000009"))
                .andExpect(jsonPath("$[0].status").value("OK"))
                .andExpect(jsonPath("$[0].arrivals[0].routeId").value("100100070"))
                .andExpect(jsonPath("$[1].stopId").value("BSB199080301"))
                .andExpect(jsonPath("$[1].status").value("LIMITED"))
                .andExpect(jsonPath("$[1].arrivals").isEmpty());
    }

    @Test
    void 서로_다른_정류장이_10곳을_넘으면_400_이고_공급자를_부르지_않는다() throws Exception {
        String eleven = "100000000,100000001,100000002,100000003,100000004,100000005,"
                + "100000006,100000007,100000008,100000009,100000010";

        mockMvc.perform(get("/api/bus/stops/arrivals").param("stopIds", eleven))
                .andExpect(status().isBadRequest());
        verify(useCase, never()).getStopsArrivals(anyList());
    }

    @Test
    void 중복과_공백을_빼고_10곳이면_통과() throws Exception {
        String tenWithDupes = "100000000,100000001,100000002,100000003,100000004,"
                + "100000005,100000006,100000007,100000008,100000009, 100000000,,100000001";
        given(useCase.getStopsArrivals(anyList())).willReturn(List.of());

        mockMvc.perform(get("/api/bus/stops/arrivals").param("stopIds", tenWithDupes))
                .andExpect(status().isOk());
    }

    @Test
    void stopIds_가_없으면_400() throws Exception {
        mockMvc.perform(get("/api/bus/stops/arrivals")).andExpect(status().isBadRequest());
    }
}
