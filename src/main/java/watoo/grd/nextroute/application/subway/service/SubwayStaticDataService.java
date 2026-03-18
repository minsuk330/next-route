package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStaticDataService implements LoadSubwayStaticDataUseCase {

	private final SubwayApiPort subwayApiPort;
	private final SubwayDataService subwayDataService;

	@Override
	public void execute() {
		loadStations();
		loadSegments();
	}

	private void loadStations() {
		List<SubwayStationInfo> allStations = subwayApiPort.getSubwayStationMaster();
		if (allStations.isEmpty()) {
			log.warn("[SubwayStatic] No stations fetched from API.");
			return;
		}

		List<SubwayStation> newStations = allStations.stream()
				.filter(info -> !subwayDataService.existsByStationId(info.stationId()))
				.map(this::toStationEntity)
				.toList();

		if (newStations.isEmpty()) {
			log.info("[SubwayStatic] All {} stations already loaded. Skipping.", allStations.size());
			return;
		}

		subwayDataService.saveAllStations(newStations);
		log.info("[SubwayStatic] Saved {} new stations ({} total from API)",
				newStations.size(), allStations.size());
	}

	private void loadSegments() {
		List<SubwaySegmentInfo> allSegments = subwayApiPort.getStationDistance();
		if (allSegments.isEmpty()) {
			log.warn("[SubwayStatic] No segments fetched from API.");
			return;
		}

		// segment는 전체 교체 (역간 거리/소요시간은 갱신될 수 있음)
		subwayDataService.deleteAllSegments();
		List<SubwaySegment> entities = allSegments.stream()
				.map(this::toSegmentEntity)
				.toList();
		subwayDataService.saveAllSegments(entities);
		log.info("[SubwayStatic] Saved {} segments", entities.size());
	}

	private SubwayStation toStationEntity(SubwayStationInfo info) {
		return SubwayStation.builder()
				.stationId(info.stationId())
				.stationName(info.stationName())
				.lineId(info.lineId())
				.lineName(info.lineName())
				.latitude(info.latitude())
				.longitude(info.longitude())
				.build();
	}

	private SubwaySegment toSegmentEntity(SubwaySegmentInfo info) {
		return SubwaySegment.builder()
				.lineId(info.lineId())
				.departStationId(info.departStationName())
				.arriveStationId(info.arriveStationName())
				.distance(info.distance())
				.travelTime(info.travelTime())
				.seq(info.seq())
				.build();
	}
}
