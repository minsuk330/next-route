package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusRidershipFetchResult;
import watoo.grd.nextroute.application.bus.dto.BusRidershipInfo;
import watoo.grd.nextroute.application.bus.dto.BusRouteRidershipRankingResponse;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BusRidershipRankingServiceTest {

	@Mock BusApiPort busApiPort;
	@InjectMocks BusRidershipRankingService service;

	@Test
	void TC_노선별_승하차합계를_누적하고_상위순으로_반환한다() {
		given(busApiPort.getBusRidershipByMonth("202603", 1000))
				.willReturn(new BusRidershipFetchResult("202603", 4, List.of(
						new BusRidershipInfo("143", "143번", 100, 80),
						new BusRidershipInfo("271", "271번", 150, 120),
						new BusRidershipInfo("143", "143번", 30, 20),
						new BusRidershipInfo("100", "100번", 20, 10)
				)));

		BusRouteRidershipRankingResponse result = service.findTopRoutes("202603", 2, 1000);

		assertThat(result.month()).isEqualTo("202603");
		assertThat(result.totalRowCount()).isEqualTo(4);
		assertThat(result.fetchedRowCount()).isEqualTo(4);
		assertThat(result.rankings()).hasSize(2);
		assertThat(result.rankings().get(0).routeNo()).isEqualTo("271");
		assertThat(result.rankings().get(0).totalUsage()).isEqualTo(270);
		assertThat(result.rankings().get(0).rowCount()).isEqualTo(1);
		assertThat(result.rankings().get(1).routeNo()).isEqualTo("143");
		assertThat(result.rankings().get(1).getOnTotal()).isEqualTo(130);
		assertThat(result.rankings().get(1).getOffTotal()).isEqualTo(100);
		assertThat(result.rankings().get(1).rowCount()).isEqualTo(2);
	}

	@Test
	void TC_총합이_동률이면_노선번호_오름차순으로_정렬한다() {
		given(busApiPort.getBusRidershipByMonth("202603", 1000))
				.willReturn(new BusRidershipFetchResult("202603", 2, List.of(
						new BusRidershipInfo("200", "200번", 50, 50),
						new BusRidershipInfo("100", "100번", 70, 30)
				)));

		BusRouteRidershipRankingResponse result = service.findTopRoutes("202603", 30, 1000);

		assertThat(result.rankings()).extracting("routeNo").containsExactly("100", "200");
	}

	@Test
	void TC_month는_yyyyMM_형식이어야_한다() {
		assertThatThrownBy(() -> service.findTopRoutes("2026-03", 30, 1000))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("month");
	}
}
