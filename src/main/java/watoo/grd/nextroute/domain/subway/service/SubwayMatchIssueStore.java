package watoo.grd.nextroute.domain.subway.service;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalEventMatchIssueRepository;

@Service
@RequiredArgsConstructor
public class SubwayMatchIssueStore {

	private final SubwayArrivalEventMatchIssueRepository subwayArrivalEventMatchIssueRepository;
	private final EntityManager entityManager;

	@Transactional
	public int deleteMatchIssuesByServiceDate(LocalDate serviceDate) {
		return subwayArrivalEventMatchIssueRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public List<SubwayArrivalEventMatchIssue> saveAllMatchIssues(List<SubwayArrivalEventMatchIssue> issues) {
		return subwayArrivalEventMatchIssueRepository.saveAll(issues);
	}

	public List<SubwayArrivalEventMatchIssue> findNoRawEventIssues(
			LocalDate serviceDate, Collection<String> lineIds) {
		return subwayArrivalEventMatchIssueRepository.findByServiceDateAndIssueTypeAndLineIdIn(
				serviceDate, MatchIssueType.NO_RAW_EVENT.name(), lineIds);
	}

	/** V2 Phase C 보강 대상 — event_count &lt; timetable_count 그룹 진단 */
	public List<SubwayArrivalEventMatchIssue> findCountMismatchIssues(
			LocalDate serviceDate, Collection<String> lineIds) {
		return subwayArrivalEventMatchIssueRepository.findByServiceDateAndIssueTypeAndLineIdIn(
				serviceDate, MatchIssueType.COUNT_MISMATCH.name(), lineIds);
	}

	/** Hibernate 1차 캐시 플러시+초기화. 청크 저장 후 heap bloat 방지. */
	@Transactional
	public void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}
}
