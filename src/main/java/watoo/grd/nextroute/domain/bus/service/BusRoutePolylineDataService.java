package watoo.grd.nextroute.domain.bus.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.BusRoutePolyline;
import watoo.grd.nextroute.domain.bus.repository.BusRoutePolylineRepository;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusRoutePolylineDataService {

	private static final String SOURCE = "SEOUL_BUS";

	private final BusRoutePolylineRepository repository;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	public List<String> findAllRouteIds() {
		return repository.findAllActiveRouteIds();
	}

	@Transactional
	public void upsert(String routeId, BusRoutePolylineData data) {
		if (data == null || data.size() < 2) {
			throw new IllegalArgumentException("Bus route polyline requires at least two points");
		}
		String serialized = serialize(data);
		OffsetDateTime fetchedAt = OffsetDateTime.now(clock);
		BusRoutePolyline entity = repository.findByRouteId(routeId)
				.map(existing -> {
					existing.refresh(data.size(), serialized, SOURCE, fetchedAt);
					return existing;
				})
				.orElseGet(() -> BusRoutePolyline.builder()
						.routeId(routeId)
						.pointCount(data.size())
						.polyline(serialized)
						.source(SOURCE)
						.fetchedAt(fetchedAt)
						.build());
		repository.save(entity);
	}

	public Optional<BusRoutePolylineData> findByRouteId(String routeId) {
		return repository.findByRouteId(routeId)
				.map(entity -> deserialize(entity.getPolyline()));
	}

	public Optional<BusRoutePolylineSnapshot> findSnapshotByRouteId(String routeId) {
		return repository.findByRouteId(routeId)
				.map(entity -> new BusRoutePolylineSnapshot(
						entity.getRouteId(),
						deserialize(entity.getPolyline()),
						entity.getFetchedAt()));
	}

	private String serialize(BusRoutePolylineData data) {
		try {
			return objectMapper.writeValueAsString(data);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to serialize bus route polyline", e);
		}
	}

	private BusRoutePolylineData deserialize(String json) {
		try {
			return objectMapper.readValue(json, BusRoutePolylineData.class);
		} catch (JsonProcessingException e) {
			throw new IllegalStateException("Failed to deserialize bus route polyline", e);
		}
	}
}
