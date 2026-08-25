package watoo.grd.nextroute.domain.bus.service;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalCandidateRaw;
import watoo.grd.nextroute.domain.bus.repository.BusArrivalCandidateRawRepository;

@Service
@RequiredArgsConstructor
public class BusArrivalCandidateStore {

	private final BusArrivalCandidateRawRepository busArrivalCandidateRawRepository;

	@Transactional
	public List<BusArrivalCandidateRaw> saveAllArrivalCandidates(List<BusArrivalCandidateRaw> candidates) {
		// lifecycle_id 기준 idempotent insert.
		// DB 커밋 후 Redis 삭제 전 크래시로 같은 snapshot이 재finalize돼도 중복 행을 만들지 않는다.
		// (1차 방어: 이미 저장된 lifecycle_id 제외 / 2차 방어: lifecycle_id unique index)
		List<String> lifecycleIds = candidates.stream()
				.map(BusArrivalCandidateRaw::getLifecycleId)
				.filter(Objects::nonNull)
				.toList();

		Set<String> existing = lifecycleIds.isEmpty()
				? Set.of()
				: new HashSet<>(busArrivalCandidateRawRepository.findExistingLifecycleIds(lifecycleIds));

		List<BusArrivalCandidateRaw> toSave = candidates.stream()
				.filter(candidate -> candidate.getLifecycleId() == null || !existing.contains(candidate.getLifecycleId()))
				.toList();

		if (toSave.isEmpty()) {
			return List.of();
		}
		return busArrivalCandidateRawRepository.saveAll(toSave);
	}
}
