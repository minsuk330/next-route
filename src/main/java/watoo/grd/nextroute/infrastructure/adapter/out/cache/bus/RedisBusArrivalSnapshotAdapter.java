package watoo.grd.nextroute.infrastructure.adapter.out.cache.bus;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.ArrivalScope;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;
import watoo.grd.nextroute.application.bus.port.out.BusArrivalSnapshotPort;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class RedisBusArrivalSnapshotAdapter implements BusArrivalSnapshotPort {

	private static final String KEY_PREFIX = "bus:arrival:active";
	private static final String SCOPE_INDEX_PREFIX = "bus:arrival:active-scopes";

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
	public Set<ArrivalScope> findActiveScopes(String routeId) {
		Set<String> members = redisTemplate.opsForSet().members(scopeIndexKey(routeId));
		if (members == null || members.isEmpty()) {
			return Set.of();
		}

		Set<ArrivalScope> scopes = new LinkedHashSet<>();
		for (String member : members) {
			ArrivalScope scope = parseScopeMember(routeId, member);
			if (scope != null) {
				scopes.add(scope);
			} else {
				log.warn("[BusArrival] Dropping malformed active scope member: route={}, member={}", routeId, member);
				redisTemplate.opsForSet().remove(scopeIndexKey(routeId), member);
			}
		}
		return scopes;
	}

	@Override
	public void save(BusArrivalActiveSnapshot snapshot) {
		String routeId = snapshot.candidate().routeId();
		String stopId = snapshot.candidate().stopId();
		Integer seq = snapshot.candidate().seq();
		try {
			redisTemplate.opsForHash().put(
					key(routeId, stopId, seq),
					snapshot.identityKey(),
					objectMapper.writeValueAsString(snapshot)
			);
			redisTemplate.opsForSet().add(scopeIndexKey(routeId), scopeMember(stopId, seq));
		} catch (JsonProcessingException e) {
			throw new RuntimeException("Failed to serialize bus arrival active snapshot", e);
		}
	}

	@Override
	public void delete(String routeId, String stopId, Integer seq, String identityKey) {
		redisTemplate.opsForHash().delete(key(routeId, stopId, seq), identityKey);
		removeScopeIndexIfEmpty(routeId, stopId, seq);
	}

	@Override
	public void cleanupScopeIfEmpty(ArrivalScope scope) {
		removeScopeIndexIfEmpty(scope.routeId(), scope.stopId(), scope.seq());
	}

	private void removeScopeIndexIfEmpty(String routeId, String stopId, Integer seq) {
		Long remaining = redisTemplate.opsForHash().size(key(routeId, stopId, seq));
		if (remaining == null || remaining == 0L) {
			redisTemplate.opsForSet().remove(scopeIndexKey(routeId), scopeMember(stopId, seq));
		}
	}

	private ArrivalScope parseScopeMember(String routeId, String member) {
		if (member == null) {
			return null;
		}
		int separator = member.lastIndexOf(':');
		if (separator <= 0 || separator == member.length() - 1) {
			return null;
		}
		String stopId = member.substring(0, separator);
		try {
			int seq = Integer.parseInt(member.substring(separator + 1));
			return new ArrivalScope(routeId, stopId, seq);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private String key(String routeId, String stopId, Integer seq) {
		return KEY_PREFIX + ":" + routeId + ":" + stopId + ":" + seq;
	}

	private String scopeIndexKey(String routeId) {
		return SCOPE_INDEX_PREFIX + ":" + routeId;
	}

	private String scopeMember(String stopId, Integer seq) {
		return stopId + ":" + seq;
	}
}
