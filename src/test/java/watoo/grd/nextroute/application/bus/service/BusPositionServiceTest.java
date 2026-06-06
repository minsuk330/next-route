package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.config.BusCollectorProperties;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusPositionRaw;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.service.BusDataService;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BusPositionServiceTest {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final LocalDateTime FIXED_NOW = LocalDateTime.of(2026, 6, 5, 10, 0, 0);

	@Mock BusApiPort busApiPort;
	@Mock BusDataService busDataService;
	@Mock BusCollectorProperties properties;
	@Mock BusApiCallBudget budget;

	private final Clock clock = Clock.fixed(FIXED_NOW.atZone(KST).toInstant(), KST);
	private BusPositionService service;

	@BeforeEach
	void setUp() {
		service = new BusPositionService(busApiPort, busDataService, properties, budget, clock);
	}

	@Test
	void TC_위치응답을_원본필드로_저장하고_호출예산을_기록한다() {
		prepareRoutes(route("100100118", "143"));
		given(budget.canMakeCall(50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118")).willReturn(List.of(positionInfo()));

		service.execute();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BusPositionRaw>> positionsCaptor =
				(ArgumentCaptor<List<BusPositionRaw>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(List.class);
		verify(busDataService).saveAllPositions(positionsCaptor.capture());
		BusPositionRaw saved = positionsCaptor.getValue().get(0);

		assertThat(saved.getCollectedAt()).isEqualTo(FIXED_NOW);
		assertThat(saved.getRouteId()).isEqualTo("100100118");
		assertThat(saved.getVehicleId()).isEqualTo("veh-1");
		assertThat(saved.getTmX()).isEqualTo(126.982001);
		assertThat(saved.getTmY()).isEqualTo(37.566500);
		assertThat(saved.getSectionOrder()).isEqualTo(12);
		assertThat(saved.getSectionDistance()).isEqualTo(345.6);
		assertThat(saved.getStopFlag()).isEqualTo("1");
		assertThat(saved.getSectionId()).isEqualTo("section-1");
		assertThat(saved.getDataTm()).isEqualTo("2026-06-05 10:00:01.0");
		assertThat(saved.getPlainNo()).isEqualTo("서울70사1234");
		assertThat(saved.getBusType()).isEqualTo(1);
		assertThat(saved.getLastStopId()).isEqualTo("111000001");
		assertThat(saved.getPosX()).isEqualTo(126.982111);
		assertThat(saved.getPosY()).isEqualTo(37.566611);
		assertThat(saved.getApiRouteId()).isEqualTo("100100118");
		assertThat(saved.getCongestion()).isEqualTo(4);
		verify(budget).recordCall();
	}

	@Test
	void TC_예산이_소진되면_남은_노선은_호출하지_않는다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(budget.canMakeCall(50000)).willReturn(true, false);
		given(busApiPort.getBusPosByRtid("100100118")).willReturn(List.of(positionInfo()));

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort, never()).getBusPosByRtid("100100119");
		verify(budget, times(1)).recordCall();
	}

	@Test
	void TC_API_실패도_호출예산에_반영하고_다음노선을_계속한다() {
		prepareRoutes(route("100100118", "143"), route("100100119", "272"));
		given(budget.canMakeCall(50000)).willReturn(true);
		given(busApiPort.getBusPosByRtid("100100118")).willThrow(new IllegalStateException("boom"));
		given(busApiPort.getBusPosByRtid("100100119")).willReturn(List.of());

		service.execute();

		verify(busApiPort).getBusPosByRtid("100100118");
		verify(busApiPort).getBusPosByRtid("100100119");
		verify(budget, times(2)).recordCall();
		verify(busDataService, never()).saveAllPositions(anyList());
	}

	private void prepareRoutes(BusRoute... routes) {
		List<String> routeNames = List.of("143", "272").subList(0, routes.length);
		given(properties.getTargetRouteNames()).willReturn(routeNames);
		given(properties.getDailyBudget()).willReturn(50000);
		given(busDataService.findRoutesByNames(routeNames)).willReturn(List.of(routes));
	}

	private BusRoute route(String routeId, String routeName) {
		return BusRoute.builder()
				.routeId(routeId)
				.routeName(routeName)
				.build();
	}

	private BusPositionInfo positionInfo() {
		return new BusPositionInfo(
				"veh-1",
				126.982001,
				37.566500,
				12,
				345.6,
				"1",
				"section-1",
				"2026-06-05 10:00:01.0",
				"서울70사1234",
				1,
				"111000001",
				126.982111,
				37.566611,
				"100100118",
				4
		);
	}
}
