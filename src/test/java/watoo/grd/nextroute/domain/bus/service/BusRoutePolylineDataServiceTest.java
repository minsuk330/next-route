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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
		when(repository.findByRouteId("route-1")).thenReturn(Optional.empty());
		BusRoutePolylineData data = new BusRoutePolylineData(List.of(
				new BusRoutePolylineData.Point(126.8, 37.5),
				new BusRoutePolylineData.Point(126.9, 37.6)
		));

		service.upsert("route-1", data);

		ArgumentCaptor<BusRoutePolyline> entityCaptor = ArgumentCaptor.forClass(BusRoutePolyline.class);
		verify(repository).save(entityCaptor.capture());
		BusRoutePolyline entity = entityCaptor.getValue();
		assertThat(entity.getRouteId()).isEqualTo("route-1");
		assertThat(entity.getPointCount()).isEqualTo(2);
		assertThat(entity.getSource()).isEqualTo("SEOUL_BUS");
		assertThat(new ObjectMapper().readTree(entity.getPolyline()).path("points")).hasSize(2);
	}

	@Test
	void TC_유효한_좌표가_2개_미만이면_갱신하지_않는다() {
		assertThatThrownBy(() -> service.upsert("route-1", new BusRoutePolylineData(List.of())))
				.isInstanceOf(IllegalArgumentException.class);
		verify(repository, never()).save(any());
	}

	@Test
	void TC_upsert는_기존_Polyline과_수집시각을_갱신한다() {
		BusRoutePolyline existing = BusRoutePolyline.builder()
				.routeId("route-1")
				.pointCount(2)
				.polyline("{\"points\":[{\"x\":126.0,\"y\":37.0},{\"x\":126.1,\"y\":37.1}]}")
				.source("SEOUL_BUS")
				.fetchedAt(java.time.OffsetDateTime.parse("2026-07-21T00:00:00Z"))
				.build();
		when(repository.findByRouteId("route-1")).thenReturn(Optional.of(existing));
		BusRoutePolylineData refreshed = new BusRoutePolylineData(List.of(
				new BusRoutePolylineData.Point(126.8, 37.5),
				new BusRoutePolylineData.Point(126.9, 37.6),
				new BusRoutePolylineData.Point(127.0, 37.7)));

		service.upsert("route-1", refreshed);

		assertThat(existing.getPointCount()).isEqualTo(3);
		assertThat(existing.getFetchedAt()).isEqualTo("2026-07-22T01:00:00Z");
		verify(repository).save(existing);
	}
}
