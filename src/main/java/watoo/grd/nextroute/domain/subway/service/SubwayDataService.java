package watoo.grd.nextroute.domain.subway.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.repository.SubwayArrivalRawRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwaySegmentRepository;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SubwayDataService {

	private final SubwayStationRepository subwayStationRepository;
	private final SubwaySegmentRepository subwaySegmentRepository;
	private final SubwayArrivalRawRepository subwayArrivalRawRepository;

	@Transactional
	public List<SubwayStation> saveAllStations(List<SubwayStation> stations) {
		return subwayStationRepository.saveAll(stations);
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
}
