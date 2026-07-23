package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusRoutePathPoint;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.exception.BusApiLimitExceededException;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.service.BusRoutePolylineDataService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusRoutePolylineBackfillServiceTest {

	@Mock BusApiPort busApiPort;
	@Mock BusRoutePolylineDataService dataService;

	BusRoutePolylineBackfillService service;

	@BeforeEach
	void setUp() {
		service = new BusRoutePolylineBackfillService(busApiPort, dataService, 0);
	}

	@Test
	void TC_미적재_노선의_좌표를_순번정렬하고_연속중복을_제거해_저장한다() {
		when(dataService.findRouteIdsWithoutPolyline()).thenReturn(List.of("route-1"));
		when(dataService.existsByRouteId("route-1")).thenReturn(false);
		when(dataService.saveIfAbsent(eq("route-1"), any())).thenReturn(true);
		when(busApiPort.getRoutePath("route-1")).thenReturn(List.of(
				new BusRoutePathPoint(3, 126.9, 37.6),
				new BusRoutePathPoint(1, 126.8, 37.5),
				new BusRoutePathPoint(2, 126.8, 37.5),
				new BusRoutePathPoint(null, 126.7, 37.4),
				new BusRoutePathPoint(4, 0.0, 0.0)
		));

		var result = service.execute();

		assertThat(result.total()).isEqualTo(1);
		assertThat(result.success()).isEqualTo(1);
		assertThat(result.aborted()).isFalse();

		ArgumentCaptor<BusRoutePolylineData> dataCaptor = ArgumentCaptor.forClass(BusRoutePolylineData.class);
		verify(dataService).saveIfAbsent(eq("route-1"), dataCaptor.capture());
		assertThat(dataCaptor.getValue().points()).containsExactly(
				new BusRoutePolylineData.Point(126.8, 37.5),
				new BusRoutePolylineData.Point(126.9, 37.6)
		);
	}

	@Test
	void TC_빈_응답과_유효점_한개는_저장하지_않는다() {
		when(dataService.findRouteIdsWithoutPolyline()).thenReturn(List.of("empty", "invalid"));
		when(dataService.existsByRouteId(any())).thenReturn(false);
		when(busApiPort.getRoutePath("empty")).thenReturn(List.of());
		when(busApiPort.getRoutePath("invalid")).thenReturn(List.of(
				new BusRoutePathPoint(1, 126.8, 37.5),
				new BusRoutePathPoint(2, 0.0, 0.0)
		));

		var result = service.execute();

		assertThat(result.empty()).isEqualTo(1);
		assertThat(result.invalid()).isEqualTo(1);
		assertThat(result.success()).isZero();
		verify(dataService, never()).saveIfAbsent(any(), any());
	}

	@Test
	void TC_이미_저장된_노선은_API를_호출하지_않는다() {
		when(dataService.findRouteIdsWithoutPolyline()).thenReturn(List.of("route-1"));
		when(dataService.existsByRouteId("route-1")).thenReturn(true);

		var result = service.execute();

		assertThat(result.skipped()).isEqualTo(1);
		verifyNoInteractions(busApiPort);
	}

	@Test
	void TC_한_노선_실패는_다음_노선_적재를_막지_않는다() {
		when(dataService.findRouteIdsWithoutPolyline()).thenReturn(List.of("bad", "good"));
		when(dataService.existsByRouteId(any())).thenReturn(false);
		when(busApiPort.getRoutePath("bad")).thenThrow(new IllegalStateException("network"));
		when(busApiPort.getRoutePath("good")).thenReturn(validPath());
		when(dataService.saveIfAbsent(eq("good"), any())).thenReturn(true);

		var result = service.execute();

		assertThat(result.failed()).isEqualTo(1);
		assertThat(result.success()).isEqualTo(1);
		assertThat(result.aborted()).isFalse();
	}

	@Test
	void TC_API_호출제한이면_남은_노선을_호출하지_않고_중단한다() {
		when(dataService.findRouteIdsWithoutPolyline()).thenReturn(List.of("limited", "not-called"));
		when(dataService.existsByRouteId("limited")).thenReturn(false);
		when(busApiPort.getRoutePath("limited"))
				.thenThrow(new BusApiLimitExceededException("limit"));

		var result = service.execute();

		assertThat(result.aborted()).isTrue();
		assertThat(result.success()).isZero();
		verify(busApiPort, never()).getRoutePath("not-called");
	}

	private List<BusRoutePathPoint> validPath() {
		return List.of(
				new BusRoutePathPoint(1, 126.8, 37.5),
				new BusRoutePathPoint(2, 126.9, 37.6)
		);
	}
}
