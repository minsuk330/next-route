package watoo.grd.nextroute.domain.bus.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.domain.bus.entity.BusRoutePolyline;
import watoo.grd.nextroute.domain.bus.repository.BusRoutePolylineRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusRoutePolylineDataServiceTest {

	@Mock BusRoutePolylineRepository repository;

	BusRoutePolylineDataService service;

	@BeforeEach
	void setUp() {
		service = new BusRoutePolylineDataService(
				repository,
				new ObjectMapper(),
				Clock.fixed(Instant.parse("2026-07-22T01:00:00Z"), ZoneOffset.UTC)
		);
	}

	@Test
	void TC_JSONB용_좌표와_pointCount를_저장한다() throws Exception {
		when(repository.existsByRouteId("route-1")).thenReturn(false);
		BusRoutePolylineData data = new BusRoutePolylineData(List.of(
				new BusRoutePolylineData.Point(126.8, 37.5),
				new BusRoutePolylineData.Point(126.9, 37.6)
		));

		assertThat(service.saveIfAbsent("route-1", data)).isTrue();

		ArgumentCaptor<BusRoutePolyline> entityCaptor = ArgumentCaptor.forClass(BusRoutePolyline.class);
		verify(repository).save(entityCaptor.capture());
		BusRoutePolyline entity = entityCaptor.getValue();
		assertThat(entity.getRouteId()).isEqualTo("route-1");
		assertThat(entity.getPointCount()).isEqualTo(2);
		assertThat(entity.getSource()).isEqualTo("SEOUL_BUS");
		assertThat(new ObjectMapper().readTree(entity.getPolyline()).path("points")).hasSize(2);
	}

	@Test
	void TC_이미_저장된_routeId면_중복_INSERT하지_않는다() {
		when(repository.existsByRouteId("route-1")).thenReturn(true);

		boolean saved = service.saveIfAbsent("route-1", new BusRoutePolylineData(List.of()));

		assertThat(saved).isFalse();
		verify(repository, never()).save(any());
	}
}
