package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.port.in.CollectSubwayArrivalUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwayArrivalRaw;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayArrivalService implements CollectSubwayArrivalUseCase {

	private final SubwayApiPort subwayApiPort;
	private final SubwayArrivalCache subwayArrivalCache;

	@Override
	public void execute() {
		LocalDateTime collectedAt = LocalDateTime.now();

		try {
			List<SubwayArrivalInfo> items = subwayApiPort.getRealtimeArrival();

			if (items.isEmpty()) {
				log.warn("[SubwayArrival] No arrival data returned from API");
				return;
			}

			List<SubwayArrivalRaw> entities = items.stream()
					.map(info -> toEntity(info, collectedAt))
					.toList();
			entities.forEach(subwayArrivalCache::update);

			log.info("[SubwayArrival] Cached {} records (cache size: {})", entities.size(), subwayArrivalCache.size());
		} catch (Exception e) {
			log.error("[SubwayArrival] Failed: {}", e.getMessage(), e);
		}
	}

	private SubwayArrivalRaw toEntity(SubwayArrivalInfo info, LocalDateTime collectedAt) {
		return SubwayArrivalRaw.builder()
				.collectedAt(collectedAt)
				.stationId(info.stationId())
				.stationName(info.stationName())
				.lineId(info.lineId())
				.direction(info.direction())
				.prevStationId(info.prevStationId())
				.nextStationId(info.nextStationId())
				.transferCount(info.transferCount())
				.ordkey(info.ordkey())
				.transferLines(info.transferLines())
				.transferStations(info.transferStations())
				.trainType(info.trainType())
				.arrivalSeconds(info.arrivalSeconds())
				.trainNo(info.trainNo())
				.destinationId(info.destinationId())
				.destinationName(info.destinationName())
				.currentMessage(info.currentMessage())
				.arrivalCode(info.arrivalCode())
				.subwayId(info.subwayId())
				.arrivalMsg3(info.arrivalMsg3())
				.receivedAt(info.receivedAt())
				.trainLineName(info.trainLineName())
				.lastTrainYn(info.lastTrainYn())
				.build();
	}
}
