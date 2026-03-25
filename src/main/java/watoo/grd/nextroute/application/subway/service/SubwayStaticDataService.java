package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationTagoInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTimetableInfo;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.port.out.TagoSubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.entity.SubwayStationTago;
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
		loadStations();
		loadSegments();
		loadTagoStations();
		loadTimetables();
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

	private void loadTagoStations() {
		// TAGO 전체 역 목록 한 번에 조회
		List<SubwayStationTagoInfo> allTagoStations = tagoSubwayApiPort.getAllStations();
		if (allTagoStations.isEmpty()) {
			log.warn("[SubwayStatic/TAGO] No stations fetched from TAGO API.");
			return;
		}

		// 기존 SubwayStation 매핑용 맵: 역명 → List<SubwayStation>
		List<SubwayStation> existingStations = subwayDataService.findAllStations();
		Map<String, List<SubwayStation>> stationsByName = existingStations.stream()
				.collect(Collectors.groupingBy(SubwayStation::getStationName));

		int savedCount = 0;
		int skippedCount = 0;

		List<SubwayStationTago> newTagoStations = new ArrayList<>();
		for (SubwayStationTagoInfo tagoInfo : allTagoStations) {
			if (subwayDataService.existsByTagoStationId(tagoInfo.tagoStationId())) {
				skippedCount++;
				continue;
			}

			SubwayStationTago.SubwayStationTagoBuilder builder = SubwayStationTago.builder()
					.tagoStationId(tagoInfo.tagoStationId())
					.stationName(tagoInfo.stationName())
					.routeName(tagoInfo.routeName());

			// routeName + 역명으로 기존 SubwayStation 매칭
			List<SubwayStation> candidates = stationsByName.get(tagoInfo.stationName());
			if (candidates != null) {
				candidates.stream()
						.filter(s -> matchRouteName(tagoInfo.routeName(), s.getLineName()))
						.findFirst()
						.ifPresent(matched -> {
							builder.stationId(matched.getStationId());
							builder.lineId(matched.getLineId());
						});
			}

			newTagoStations.add(builder.build());
		}

		if (!newTagoStations.isEmpty()) {
			subwayDataService.saveAllTagoStations(newTagoStations);
			savedCount = newTagoStations.size();
		}

		log.info("[SubwayStatic/TAGO] Saved {} TAGO stations, skipped {} (already exists), {} total from API",
				savedCount, skippedCount, allTagoStations.size());
	}

	private void loadTimetables() {
		if (subwayDataService.countTimetables() > 0) {
			log.info("[SubwayStatic/TAGO] Timetables already loaded. Skipping. (Use daily reload to refresh.)");
			return;
		}

		List<SubwayStationTago> matchedStations = subwayDataService.findMatchedTagoStations();
		if (matchedStations.isEmpty()) {
			log.warn("[SubwayStatic/TAGO] No matched TAGO stations found. Skipping timetable loading.");
			return;
		}

		log.info("[SubwayStatic/TAGO] Loading timetables for {} matched stations", matchedStations.size());

		int totalSaved = 0;
		int stationCount = 0;

		for (SubwayStationTago station : matchedStations) {
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
						stationCount, matchedStations.size(), totalSaved);
			}
		}

		log.info("[SubwayStatic/TAGO] Timetable loading complete: {} records for {} stations",
				totalSaved, stationCount);
	}

	/** TAGO routeName(예: "1호선")과 기존 lineName(예: "01호선") 매칭 */
	private boolean matchRouteName(String tagoRouteName, String lineName) {
		if (tagoRouteName == null || lineName == null) return false;
		// 숫자만 추출하여 비교 (예: "1호선" → "1", "01호선" → "01" → 1)
		String tagoNum = tagoRouteName.replaceAll("[^0-9]", "");
		String lineNum = lineName.replaceAll("[^0-9]", "");
		if (tagoNum.isEmpty() || lineNum.isEmpty()) return false;
		try {
			return Integer.parseInt(tagoNum) == Integer.parseInt(lineNum);
		} catch (NumberFormatException e) {
			return tagoNum.equals(lineNum);
		}
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
