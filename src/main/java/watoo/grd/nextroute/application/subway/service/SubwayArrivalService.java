package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.port.in.CollectSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayArrivalService implements CollectSubwayArrivalUseCase {

	private final SubwayApiPort subwayApiPort;
	private final SubwayDataService subwayDataService;

	@Override
	public void execute() {
		Set<String> stationNames = subwayDataService.findAllStations().stream()
				.map(SubwayStation::getStationName)
				.collect(Collectors.toSet());

		if (stationNames.isEmpty()) {
			log.warn("[SubwayArrival] No stations to collect. Run StaticDataLoader first.");
			return;
		}

		LocalDateTime collectedAt = LocalDateTime.now();
		int totalSaved = 0;

		log.info("[SubwayArrival] Starting collection for {} stations", stationNames.size());

		for (String stationName : stationNames) {
			try {
				List<SubwayArrivalInfo> items = subwayApiPort.getRealtimeArrival(stationName);
				List<SubwayArrivalRaw> entities = items.stream()
						.map(info -> toEntity(info, collectedAt))
						.toList();
				subwayDataService.saveAllArrivals(entities);
				totalSaved += entities.size();
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[SubwayArrival] Failed for station {}: {}", stationName, e.getMessage());
			}
		}

		log.info("[SubwayArrival] Completed. Saved {} records", totalSaved);
	}

	private SubwayArrivalRaw toEntity(SubwayArrivalInfo info, LocalDateTime collectedAt) {
		return SubwayArrivalRaw.builder()
				.collectedAt(collectedAt)
				.stationId(info.stationId())
				.stationName(info.stationName())
				.lineId(info.lineId())
				.direction(info.direction())
				.arrivalSeconds(info.arrivalSeconds())
				.trainNo(info.trainNo())
				.destinationName(info.destinationName())
				.currentMessage(info.currentMessage())
				.arrivalCode(info.arrivalCode())
				.build();
	}
}
