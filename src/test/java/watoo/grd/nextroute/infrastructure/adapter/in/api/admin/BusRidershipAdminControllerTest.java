package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRank;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.application.bus.service.BusRidershipRankingService;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BusRidershipAdminController.class)
class BusRidershipAdminControllerTest {

	@Autowired MockMvc mockMvc;

	@MockBean BusRidershipRankingService service;

	@Test
	void TC_기본파라미터로_버스이용량_topRoutes를_호출한다() throws Exception {
		given(service.findTopRoutes("202603", 30, 1000))
				.willReturn(new BusRouteRidershipRankingResponse("202603", 40000, 40000, List.of(
						new BusRouteRidershipRank(1, "143", "143번", 100, 80, 180, 10)
				)));

		mockMvc.perform(post("/api/admin/bus/ridership/top-routes"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.month").value("202603"))
				.andExpect(jsonPath("$.totalRowCount").value(40000))
				.andExpect(jsonPath("$.fetchedRowCount").value(40000))
				.andExpect(jsonPath("$.rankings[0].rank").value(1))
				.andExpect(jsonPath("$.rankings[0].routeNo").value("143"))
				.andExpect(jsonPath("$.rankings[0].totalUsage").value(180));

		verify(service).findTopRoutes("202603", 30, 1000);
	}
}
