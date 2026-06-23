package watoo.grd.nextroute.infrastructure.adapter.in.api.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRank;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.application.bus.service.BusRidershipRankingService;
import watoo.grd.nextroute.application.bus.service.WeeklyRouteRotationService;

import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BusRidershipAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
class BusRidershipAdminControllerTest {

	@Autowired MockMvc mockMvc;

	@MockBean BusRidershipRankingService service;
	@MockBean WeeklyRouteRotationService weeklyRouteRotationService;

	@Test
	void TC_기본파라미터로_버스이용량_topRoutes를_호출한다() throws Exception {
		given(service.findTopRoutes("202603", 30, 0, 1000))
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

		verify(service).findTopRoutes("202603", 30, 0, 1000);
	}

	@Test
	void TC_로테이션_수동실행은_rotate를_호출한다() throws Exception {
		given(weeklyRouteRotationService.rotate())
				.willReturn(new WeeklyRouteRotationService.RotationResult(1, 30, "202603", 30, false));

		mockMvc.perform(post("/api/admin/bus/rotation/run"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.bucket").value(1))
				.andExpect(jsonPath("$.offset").value(30))
				.andExpect(jsonPath("$.routeCount").value(30));

		verify(weeklyRouteRotationService).rotate();
	}
}
