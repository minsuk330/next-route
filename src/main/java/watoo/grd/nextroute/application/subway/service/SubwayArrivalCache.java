package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SubwayArrivalCache {

	record CacheKey(String trainNo, String stationId, String lineId, String direction) {}

	private final ConcurrentHashMap<CacheKey, SubwayArrivalRaw> cache = new ConcurrentHashMap<>();
	private final Set<CacheKey> dirtyKeys = ConcurrentHashMap.newKeySet();

	/** API 응답 레코드를 캐시에 반영 */
	public void update(SubwayArrivalRaw incoming) {
		CacheKey key = toKey(incoming);
		cache.compute(key, (k, existing) -> {
			if (existing == null) {
				dirtyKeys.add(k);
				return incoming;
			}
			// 기존이 도착 확정이면 유지
			if ("1".equals(existing.getArrivalCode())) {
				return existing;
			}
			// 새 레코드가 도착 확정이면 교체
			if ("1".equals(incoming.getArrivalCode())) {
				dirtyKeys.add(k);
				return incoming;
			}
			// 둘 다 아니면 arrivalSeconds 더 작은 걸 채택
			if (incoming.getArrivalSeconds() != null && existing.getArrivalSeconds() != null
					&& incoming.getArrivalSeconds() < existing.getArrivalSeconds()) {
				dirtyKeys.add(k);
				return incoming;
			}
			return existing;
		});
	}

	/** dirty 항목 추출 + 도착 확정 항목 캐시에서 제거 */
	public List<SubwayArrivalRaw> drainDirty() {
		List<SubwayArrivalRaw> result = new ArrayList<>();
		Iterator<CacheKey> it = dirtyKeys.iterator();
		while (it.hasNext()) {
			CacheKey key = it.next();
			SubwayArrivalRaw record = cache.get(key);
			if (record != null) {
				result.add(record);
				// 도착 확정 → 캐시에서 삭제 (순환선 재방문 대응)
				if ("1".equals(record.getArrivalCode())) {
					cache.remove(key);
				}
			}
			it.remove();
		}
		return result;
	}

	/** 일일 초기화 */
	public void clear() {
		int size = cache.size();
		cache.clear();
		dirtyKeys.clear();
		log.info("[SubwayCache] Cleared. Was {} entries", size);
	}

	public int size() {
		return cache.size();
	}

	private CacheKey toKey(SubwayArrivalRaw raw) {
		return new CacheKey(raw.getTrainNo(), raw.getStationId(), raw.getLineId(), raw.getDirection());
	}
}
