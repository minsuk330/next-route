package watoo.grd.nextroute.application.subway.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.port.in.LoadSubwayStaticDataUseCase;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.domain.subway.entity.SubwayStation;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayStaticDataService implements LoadSubwayStaticDataUseCase {

	private static final String[] SUBWAY_SEED_STATIONS = {
			"서울역", "시청", "종각", "종로3가", "종로5가", "동대문", "신설동",
			"강남", "역삼", "삼성", "잠실", "건대입구", "왕십리", "성수",
			"홍대입구", "신촌", "이대", "충정로", "을지로입구", "을지로3가",
			"교대", "서초", "방배", "사당", "이수", "총신대입구",
			"여의도", "영등포구청", "당산", "합정", "망원", "마포구청",
			"노원", "창동", "수유", "미아", "길음", "성신여대입구",
			"동작", "이촌", "용산", "숙대입구", "남영",
			"천호", "강동구청", "올림픽공원", "몽촌토성", "잠실나루",
			"김포공항", "개화산", "마곡나루", "가양", "염창",
			"선릉", "한티", "도곡", "대치", "학여울", "대청", "일원"
	};

	private final SubwayApiPort subwayApiPort;
	private final SubwayDataService subwayDataService;

	@Override
	public void execute() {
		Map<String, SubwayStation> uniqueStations = new LinkedHashMap<>();

		for (String seedStation : SUBWAY_SEED_STATIONS) {
			try {
				List<SubwayArrivalInfo> items = subwayApiPort.getRealtimeArrival(seedStation);

				for (SubwayArrivalInfo item : items) {
					uniqueStations.putIfAbsent(item.stationId(),
							SubwayStation.builder()
									.stationId(item.stationId())
									.stationName(item.stationName())
									.lineId(item.lineId())
									.lineName(toLineName(item.lineId()))
									.build());
				}

				Thread.sleep(200);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			} catch (Exception e) {
				log.error("[StaticData] Failed to load subway station '{}': {}", seedStation, e.getMessage());
			}
		}

		if (uniqueStations.isEmpty()) {
			log.warn("[StaticData] No subway stations fetched from API.");
			return;
		}

		subwayDataService.saveAllStations(new ArrayList<>(uniqueStations.values()));
		log.info("[StaticData] Saved {} subway stations", uniqueStations.size());
	}

	private String toLineName(String lineId) {
		return switch (lineId) {
			case "1001" -> "1호선";
			case "1002" -> "2호선";
			case "1003" -> "3호선";
			case "1004" -> "4호선";
			case "1005" -> "5호선";
			case "1006" -> "6호선";
			case "1007" -> "7호선";
			case "1008" -> "8호선";
			case "1009" -> "9호선";
			default -> lineId;
		};
	}
}
