package watoo.grd.nextroute.application.bus.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusRouteInfo;
import watoo.grd.nextroute.application.bus.dto.BusRouteStopInfo;
import watoo.grd.nextroute.application.bus.port.in.LoadBusRoutePolylineUseCase;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.domain.bus.entity.BusRoute;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.service.BusCatalogWriter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusStaticDataServiceTest {

	@Mock BusApiPort busApiPort;
	@Mock BusRouteRepository busRouteRepository;
	@Mock BusCatalogWriter busCatalogWriter;
	@Mock LoadBusRoutePolylineUseCase loadBusRoutePolyline;

	@Test
	@SuppressWarnings("unchecked")
	void TC_기존_노선도_매_실행에_노선_정류장_경유정보_Polyline을_갱신한다() {
		BusRoute existingRoute = BusRoute.builder().routeId("R1").routeName("old").build();
		when(busApiPort.getBusRouteIds()).thenReturn(List.of("R1"));
		when(busApiPort.getRouteInfo("R1")).thenReturn(List.of(new BusRouteInfo(
				"R1", "new", 3, "start", "end", 10,
				"040000", "230000", "company", 10.0, "N", null, null)));
		when(busRouteRepository.findAll()).thenReturn(List.of(existingRoute));
		when(busApiPort.getStationByRoute("R1")).thenReturn(List.of(
				new BusRouteStopInfo("R1", 1, "0", "A", "A stop", "10001",
						37.5, 126.9, 0.0, "end", "N", "1", null, null, "B", 20.0),
				new BusRouteStopInfo("R1", 2, "S1", "B", "B stop", "10002",
						37.5, 126.91, 880.0, "end", "N", "2", null, null, "B", 20.0)));
		when(busCatalogWriter.upsertStops(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

		new BusStaticDataService(busApiPort, busRouteRepository, busCatalogWriter, loadBusRoutePolyline).execute();

		verify(busCatalogWriter).upsertRoutes(argThat(routes ->
				routes.size() == 1 && routes.get(0).getRouteName().equals("new")));
		ArgumentCaptor<List<BusRouteStop>> stops = ArgumentCaptor.forClass(List.class);
		verify(busCatalogWriter).replaceRouteStops(eq("R1"), stops.capture());
		assertThat(stops.getValue()).extracting(BusRouteStop::getSeq).containsExactly(1, 2);
		assertThat(stops.getValue().get(1).getSectionDistance()).isEqualTo(880.0);
		verify(loadBusRoutePolyline).execute();
	}
}
