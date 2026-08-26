package watoo.grd.nextroute.domain.bus.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.bus.entity.BusArrivalLabelEvent;
import watoo.grd.nextroute.domain.bus.repository.BusArrivalCandidateLabelRow;
import watoo.grd.nextroute.domain.bus.repository.BusArrivalCandidateRawRepository;
import watoo.grd.nextroute.domain.bus.repository.BusArrivalLabelEventRepository;
import watoo.grd.nextroute.domain.bus.repository.BusPositionLabelRow;
import watoo.grd.nextroute.domain.bus.repository.BusPositionRawRepository;

@Service
@RequiredArgsConstructor
public class BusArrivalLabelStore {

	private final BusArrivalLabelEventRepository busArrivalLabelEventRepository;
	private final BusArrivalCandidateRawRepository busArrivalCandidateRawRepository;
	private final BusPositionRawRepository busPositionRawRepository;
	private final EntityManager entityManager;

	@Transactional
	public int deleteLabelEventsByServiceDate(LocalDate serviceDate) {
		return busArrivalLabelEventRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public List<BusArrivalLabelEvent> saveAllLabelEvents(List<BusArrivalLabelEvent> events) {
		return busArrivalLabelEventRepository.saveAll(events);
	}

	public List<String> findCandidateRouteIdsByFinalizedAtBetween(
			LocalDateTime from, LocalDateTime to) {
		return busArrivalCandidateRawRepository.findDistinctRouteIdsByFinalizedAtBetween(from, to);
	}

	public List<BusArrivalCandidateLabelRow> findCandidateLabelRowsByRoute(
			String routeId, LocalDateTime from, LocalDateTime to) {
		return busArrivalCandidateRawRepository.findLabelRowsByRouteIdAndFinalizedAtBetween(routeId, from, to);
	}

	public List<BusPositionLabelRow> findPositionLabelRowsByRoute(
			String routeId, LocalDateTime from, LocalDateTime to) {
		return busPositionRawRepository.findLabelRowsByRouteIdAndCollectedAtBetween(routeId, from, to);
	}

	/** Hibernate 1차 캐시 플러시+초기화. 청크 저장 후 heap bloat 방지. */
	@Transactional
	public void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
