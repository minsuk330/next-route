package watoo.grd.nextroute.domain.subway.service;

import java.util.Collection;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.MatchIssueType;
import watoo.grd.nextroute.domain.subway.entity.MlSubwayDelayTruth;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEvent;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalEventMatchIssue;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.repository.MlSubwayDelayTruthRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalEventMatchIssueRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalEventRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalRawRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwaySegmentRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository.TimetableCoverageProjection;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubwayDataService {

	private final SubwayStationRepository subwayStationRepository;
	private final SubwaySegmentRepository subwaySegmentRepository;
	private final SubwayArrivalRawRepository subwayArrivalRawRepository;
	private final SubwayTimetableRepository subwayTimetableRepository;
	private final SubwayArrivalEventRepository subwayArrivalEventRepository;
	private final SubwayArrivalEventMatchIssueRepository subwayArrivalEventMatchIssueRepository;
	private final MlSubwayDelayTruthRepository mlSubwayDelayTruthRepository;

	@Transactional
	public List<SubwayStation> saveAllStations(List<SubwayStation> stations) {
		List<SubwayStation> saved = subwayStationRepository.saveAll(stations);
		subwayStationRepository.backfillGeom();
		return saved;
	}

	@Transactional
	public List<SubwaySegment> saveAllSegments(List<SubwaySegment> segments) {
		return subwaySegmentRepository.saveAll(segments);
	}

	@Transactional
	public List<SubwayArrivalRaw> saveAllArrivals(List<SubwayArrivalRaw> arrivals) {
		return subwayArrivalRawRepository.saveAll(arrivals);
	}

	@Transactional
	public ArrivalRawInsertResult insertArrivalRawIgnoreDuplicates(List<SubwayArrivalRaw> raws) {
		int insertedRows = 0;
		int attemptedCode1Rows = 0;
		int insertedCode1Rows = 0;

		for (SubwayArrivalRaw raw : raws) {
			boolean code1 = "1".equals(raw.getArrivalCode());
			if (code1) {
				attemptedCode1Rows++;
			}

			int inserted = subwayArrivalRawRepository.insertIgnore(raw);
			insertedRows += inserted;

			if (code1) {
				insertedCode1Rows += inserted;
			}
		}

		return new ArrivalRawInsertResult(
				raws.size(),
				insertedRows,
				raws.size() - insertedRows,
				attemptedCode1Rows,
				insertedCode1Rows,
				attemptedCode1Rows - insertedCode1Rows
		);
	}

	public List<SubwayStation> findAllStations() {
		return subwayStationRepository.findAll();
	}

	public List<SubwaySegment> findAllSegments() {
		return subwaySegmentRepository.findAll();
	}

	public Optional<SubwayStation> findByStationId(String stationId) {
		return subwayStationRepository.findByStationId(stationId);
	}

	public List<SubwayStation> findStationsByLine(String lineId) {
		return subwayStationRepository.findByLineId(lineId);
	}

	public boolean existsByStationId(String stationId) {
		return subwayStationRepository.existsByStationId(stationId);
	}

	public long countSegments() {
		return subwaySegmentRepository.count();
	}

	@Transactional
	public void deleteAllSegments() {
		subwaySegmentRepository.deleteAll();
	}

	// ===== Timetable =====

	@Transactional
	public List<SubwayTimetable> saveAllTimetables(List<SubwayTimetable> timetables) {
		return subwayTimetableRepository.saveAll(timetables);
	}

	@Transactional
	public void deleteAllTimetables() {
		subwayTimetableRepository.deleteAll();
	}

	public long countTimetables() {
		return subwayTimetableRepository.count();
	}

	// ===== Arrival =====

	public List<SubwayArrivalRaw> findLatestArrivalsByStationId(String stationId, LocalDateTime from) {
		return subwayArrivalRawRepository.findByStationIdAndCollectedAtAfter(stationId, from);
	}

	public List<SubwayArrivalRaw> findArrivalCandidatesInRange(String fromReceivedAt, String toReceivedAt) {
		return subwayArrivalRawRepository.findArrivalCandidatesInRange(fromReceivedAt, toReceivedAt);
	}

	public List<SubwayArrivalRaw> findPrevDepartureCandidatesInRange(
			String fromReceivedAt, String toReceivedAt, Collection<String> lineIds) {
		return subwayArrivalRawRepository.findPrevDepartureCandidatesInRange(fromReceivedAt, toReceivedAt, lineIds);
	}

	// ===== ArrivalEvent =====

	@Transactional
	public int deleteArrivalEventsByServiceDate(LocalDate serviceDate) {
		return subwayArrivalEventRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public int deleteArrivalEventsByServiceDateAndEventSource(LocalDate serviceDate, String eventSource) {
		return subwayArrivalEventRepository.deleteByServiceDateAndEventSource(serviceDate, eventSource);
	}

	@Transactional
	public List<SubwayArrivalEvent> saveAllArrivalEvents(List<SubwayArrivalEvent> events) {
		return subwayArrivalEventRepository.saveAll(events);
	}

	public List<SubwayArrivalEvent> findArrivalEventsByServiceDate(LocalDate serviceDate) {
		return subwayArrivalEventRepository.findByServiceDate(serviceDate);
	}

	public SubwayStation findByStationNameLikeAndLineName(String name, String lineName) {
		return subwayStationRepository.findByStationNameLikeAndLineName(name, lineName);
	}

	public List<NearbySubwayStationProjection> findNearbyStations(double lat, double lng, double radiusMeters, int limit) {
		return subwayStationRepository.findNearby(lat, lng, radiusMeters, limit);
	}

	public List<SubwayStation> findAllWithoutCoordinates() {
		return subwayStationRepository.findByLatitudeIsNull();
	}

	@Transactional
	public void updateCoordinates(Long id, double lat, double lon) {
		subwayStationRepository.findById(id)
				.ifPresent(s -> s.updateCoordinates(lat, lon));
	}

	// ===== ArrivalEventMatchIssue =====

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

	// ===== MlSubwayDelayTruth =====

	@Transactional
	public int deleteDelayTruthByServiceDate(LocalDate serviceDate) {
		return mlSubwayDelayTruthRepository.deleteByServiceDate(serviceDate);
	}

	@Transactional
	public List<MlSubwayDelayTruth> saveAllDelayTruth(List<MlSubwayDelayTruth> truths) {
		return mlSubwayDelayTruthRepository.saveAll(truths);
	}

	public List<MlSubwayDelayTruth> findDelayTruthByServiceDate(LocalDate serviceDate) {
		return mlSubwayDelayTruthRepository.findByServiceDate(serviceDate);
	}

	public long countDelayTruthByServiceDate(LocalDate serviceDate) {
		return mlSubwayDelayTruthRepository.countByServiceDate(serviceDate);
	}

	// ===== Timetable Coverage =====

	public List<TimetableCoverageProjection> findTimetableCoverage(String dayType) {
		return subwayTimetableRepository.findDistinctCoverage(dayType);
	}

	// ===== Station lookup =====

	public Optional<SubwayStation> findByStationIdAndLineId(String stationId, String lineId) {
		return subwayStationRepository.findByStationIdAndLineId(stationId, lineId);
	}

	public List<SubwayStation> findByLineIdAndTagoStationId(String lineId, String tagoStationId) {
		return subwayStationRepository.findByLineIdAndTagoStationId(lineId, tagoStationId);
	}

	public List<SubwayStation> findStationsByLineIdIn(Collection<String> lineIds) {
		return subwayStationRepository.findByLineIdIn(lineIds);
	}

	public List<SubwayStation> findMappableStations() {
		return subwayStationRepository.findByTagoStationIdIsNotNull();
	}

	public List<SubwayTimetable> findTimetablesByDayTypeAndLineIdIn(String dayType, Collection<String> lineIds) {
		return subwayTimetableRepository.findByDayTypeAndLineIdIn(dayType, lineIds);
	}
}
