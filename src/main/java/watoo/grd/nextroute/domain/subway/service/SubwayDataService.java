package watoo.grd.nextroute.domain.subway.service;

import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayStationTago;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalRawRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwaySegmentRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationTagoRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayTimetableRepository;

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
	private final SubwayStationTagoRepository subwayStationTagoRepository;
	private final SubwayTimetableRepository subwayTimetableRepository;

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

	// ===== TAGO Station =====

	@Transactional
	public List<SubwayStationTago> saveAllTagoStations(List<SubwayStationTago> stations) {
		return subwayStationTagoRepository.saveAll(stations);
	}

	public List<SubwayStationTago> findMatchedTagoStations() {
		return subwayStationTagoRepository.findByStationIdIsNotNull();
	}

	public boolean existsByTagoStationId(String tagoStationId) {
		return subwayStationTagoRepository.existsByTagoStationId(tagoStationId);
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

	public SubwayStation findByStationNameLikeAndLineName(String name, String lineName) {
		return subwayStationRepository.findByStationNameLikeAndLineName(name, lineName);
	}

	public List<NearbySubwayStationProjection> findNearbyStations(double lat, double lng, double radiusMeters, int limit) {
		return subwayStationRepository.findNearby(lat, lng, radiusMeters, limit);
	}
}
