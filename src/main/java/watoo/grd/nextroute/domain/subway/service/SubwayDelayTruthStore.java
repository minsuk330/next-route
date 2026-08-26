package watoo.grd.nextroute.domain.subway.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.MlSubwayDelayTruth;
import watoo.grd.nextroute.domain.subway.repository.MlSubwayDelayTruthRepository;

@Service
@RequiredArgsConstructor
public class SubwayDelayTruthStore {

	private final MlSubwayDelayTruthRepository mlSubwayDelayTruthRepository;
	private final EntityManager entityManager;

	@Transactional
	public int deleteDelayTruthByServiceDate(LocalDate serviceDate) {
		return mlSubwayDelayTruthRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public List<MlSubwayDelayTruth> saveAllDelayTruth(List<MlSubwayDelayTruth> truths) {
		return mlSubwayDelayTruthRepository.saveAll(truths);
	}

	/** Hibernate 1차 캐시 플러시+초기화. 청크 저장 후 heap bloat 방지. */
	@Transactional
	public void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
