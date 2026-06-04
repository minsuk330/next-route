package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisBusArrivalSnapshotAdapter implements BusArrivalSnapshotPort {

	private static final String KEY_PREFIX = "bus:arrival:active";

	private final RedisTemplate<String, String> redisTemplate;
	private final ObjectMapper objectMapper;

	@Override
	public Map<String, BusArrivalActiveSnapshot> findActive(String routeId, String stopId, Integer seq) {
		Map<Object, Object> entries = redisTemplate.opsForHash().entries(key(routeId, stopId, seq));
		if (entries.isEmpty()) {
			return Map.of();
		}

		Map<String, BusArrivalActiveSnapshot> snapshots = new LinkedHashMap<>();
		for (Map.Entry<Object, Object> entry : entries.entrySet()) {
			if (entry.getKey() == null || entry.getValue() == null) {
				continue;
			}
			try {
				BusArrivalActiveSnapshot snapshot = objectMapper.readValue(
						entry.getValue().toString(),
						BusArrivalActiveSnapshot.class
				);
				snapshots.put(entry.getKey().toString(), snapshot);
			} catch (JsonProcessingException e) {
				log.warn("[BusArrival] Failed to parse active snapshot {}: {}",
						entry.getKey(), e.getMessage());
			}
		}
		return snapshots;
	}

	@Override
	public void save(BusArrivalActiveSnapshot snapshot) {
		try {
			redisTemplate.opsForHash().put(
					key(snapshot.candidate().routeId(), snapshot.candidate().stopId(), snapshot.candidate().seq()),
					snapshot.identityKey(),
					objectMapper.writeValueAsString(snapshot)
			);
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to serialize bus arrival active snapshot", e);
		}
	}

	@Override
	public void delete(String routeId, String stopId, Integer seq, String identityKey) {
		redisTemplate.opsForHash().delete(key(routeId, stopId, seq), identityKey);
	}

	private String key(String routeId, String stopId, Integer seq) {
		return KEY_PREFIX + ":" + routeId + ":" + stopId + ":" + seq;
	}
}
