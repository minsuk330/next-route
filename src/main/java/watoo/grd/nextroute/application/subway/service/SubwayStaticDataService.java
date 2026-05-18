package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTimetableInfo;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.TagoSubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayTimetable;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStaticDataService implements LoadSubwayStaticDataUseCase {

	private static final String[] DAY_TYPES = {"01", "02", "03"};
	private static final String[] DIRECTIONS = {"U", "D"};
	private static final long API_THROTTLE_MS = 200;

	private final SubwayApiPort subwayApiPort;
	private final TagoSubwayApiPort tagoSubwayApiPort;
	private final SubwayDataService subwayDataService;

	@Override
	public void execute() {
		loadSegments();
		loadTimetables();
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

	private void loadTimetables() {
		// 2안: subway_station.tago_station_id 기준 전체 재적재
		// (subway_station_tago.station_id 기준은 비숫자 노선 1032/1063/1065/.../1094를 누락시킴)
		List<SubwayStation> mappableStations = subwayDataService.findMappableStations();
		if (mappableStations.isEmpty()) {
			log.warn("[SubwayStatic/TAGO] No mappable stations found. Skipping timetable loading.");
			return;
		}

		// line_id + tago_station_id 중복 제거 (같은 역이 여러 row로 존재할 수 있음)
		Map<String, SubwayStation> uniqueTargets = mappableStations.stream()
				.collect(Collectors.toMap(
						s -> s.getLineId() + "|" + s.getTagoStationId(),
						s -> s,
						(a, b) -> a,
						java.util.LinkedHashMap::new));
		List<SubwayStation> targetStations = new ArrayList<>(uniqueTargets.values());

		List<String> lineIds = targetStations.stream()
				.map(SubwayStation::getLineId)
				.distinct()
				.sorted()
				.toList();
		log.info("[SubwayStatic/TAGO] Timetable load targets: stations={}, lineIds={}",
				targetStations.size(), lineIds);

		// 전체 재적재: 기존 timetable 제거 후 재로딩
		long existing = subwayDataService.countTimetables();
		subwayDataService.deleteAllTimetables();
		log.info("[SubwayStatic/TAGO] Deleted {} existing timetable rows for full reload", existing);

		int totalSaved = 0;
		int stationCount = 0;

		for (SubwayStation station : targetStations) {
			stationCount++;
			int stationSaved = 0;

			for (String dayType : DAY_TYPES) {
				for (String direction : DIRECTIONS) {
					List<SubwayTimetableInfo> timetables = tagoSubwayApiPort.getTimetable(
							station.getTagoStationId(), dayType, direction);

					if (!timetables.isEmpty()) {
						List<SubwayTimetable> entities = timetables.stream()
								.map(info -> toTimetableEntity(info, station.getLineId()))
								.toList();
						subwayDataService.saveAllTimetables(entities);
						stationSaved += entities.size();
					}

					throttle();
				}
			}

			totalSaved += stationSaved;
			if (stationCount % 50 == 0) {
				log.info("[SubwayStatic/TAGO] Timetable progress: {}/{} stations, {} records so far",
						stationCount, targetStations.size(), totalSaved);
			}
		}

		log.info("[SubwayStatic/TAGO] Timetable loading complete: {} records for {} stations",
				totalSaved, stationCount);
	}

	private void throttle() {
		try {
			Thread.sleep(API_THROTTLE_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	// ===== Entity 변환 =====

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

	private SubwayTimetable toTimetableEntity(SubwayTimetableInfo info, String lineId) {
		return SubwayTimetable.builder()
				.tagoStationId(info.subwayStationId())
				.stationName(info.subwayStationNm())
				.lineId(lineId)
				.direction(info.upDownTypeCode())
				.dayType(info.dailyTypeCode())
				.depTime(info.depTime())
				.arrTime(info.arrTime())
				.endStationName(info.endSubwayStationNm())
				.subwayRouteId(info.subwayRouteId())
				.build();
	}
}
