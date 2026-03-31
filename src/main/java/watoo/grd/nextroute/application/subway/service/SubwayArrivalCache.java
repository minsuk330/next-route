package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class SubwayArrivalCache {

	record CacheKey(String trainNo, String stationId, String lineId, String direction) {}

	private final Map<CacheKey, SubwayArrivalRaw> cache = new HashMap<>();
	private final Set<CacheKey> dirtyKeys = new HashSet<>();

	/** API 응답 레코드를 캐시에 반영 */
	public synchronized void update(SubwayArrivalRaw incoming) {
		CacheKey key = toKey(incoming);
		SubwayArrivalRaw existing = cache.get(key);

		if (existing == null) {
			cache.put(key, incoming);
			dirtyKeys.add(key);
			return;
		}
		// 기존이 도착 확정이면 유지
		if ("1".equals(existing.getArrivalCode())) {
			return;
		}
		// 새 레코드가 도착 확정이면 교체
		if ("1".equals(incoming.getArrivalCode())) {
      /// 이거 교체가 되는거임?
			cache.put(key, incoming);
			dirtyKeys.add(key);
			return;
		}
		// 둘 다 아니면 arrivalSeconds 더 작은 걸 채택
		if (incoming.getArrivalSeconds() != null && existing.getArrivalSeconds() != null
				&& incoming.getArrivalSeconds() < existing.getArrivalSeconds()) {
			cache.put(key, incoming);
			dirtyKeys.add(key);
		}
	}

	/** dirty 항목 추출 + stale/도착확정 항목 캐시에서 제거 */
	public synchronized List<SubwayArrivalRaw> drainDirty() {
		List<SubwayArrivalRaw> result = new ArrayList<>();
		for (CacheKey key : dirtyKeys) {
			SubwayArrivalRaw record = cache.get(key);
			if (record != null) {
				result.add(record);
			}
		}
		// 이전 flush 이후 갱신되지 않은 stale 항목 제거
		cache.keySet().retainAll(dirtyKeys);
		// 도착 확정 항목도 제거 (순환선 재방문 대응)
		cache.entrySet().removeIf(e -> "1".equals(e.getValue().getArrivalCode()));
		dirtyKeys.clear();
		return result;
	}

	/** 일일 초기화 */
	public synchronized void clear() {
		int size = cache.size();
		cache.clear();
		dirtyKeys.clear();
		log.info("[SubwayCache] Cleared. Was {} entries", size);
	}

	public synchronized int size() {
		return cache.size();
	}

	private CacheKey toKey(SubwayArrivalRaw raw) {
		return new CacheKey(raw.getTrainNo(), raw.getStationId(), raw.getLineId(), raw.getDirection());
	}
}
