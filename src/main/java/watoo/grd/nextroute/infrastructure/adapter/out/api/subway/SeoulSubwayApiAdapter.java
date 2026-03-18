package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static watoo.grd.nextroute.common.util.ParseUtils.parseDouble;
import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

@Slf4j
@Component
public class SeoulSubwayApiAdapter implements SubwayApiPort {

	private static final int MAX_RETRIES = 3;
	private static final int PAGE_SIZE = 1000;

	private final RestTemplate restTemplate;
	private final String apiKey;
	private final String baseUrl;
	private final String masterKey;
	private final String routeKey;
	private final String seoulBaseApiUrl;

	public SeoulSubwayApiAdapter(
			RestTemplate restTemplate,
			@Value("${seoul.api.subway-arrival-key}") String apiKey,
			@Value("${seoul.api.subway-base-url}") String baseUrl,
			@Value("${seoul.api.subway-master-key}") String masterKey,
			@Value("${seoul.api.subway-route-key}") String routeKey,
			@Value("${seoul.api.seoul-base-api-url}") String seoulBaseApiUrl) {
		this.restTemplate = restTemplate;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.masterKey = masterKey;
		this.routeKey = routeKey;
		this.seoulBaseApiUrl = seoulBaseApiUrl;
	}

	@Override
	public List<SubwayStationInfo> getSubwayStationMaster() {
		List<SubwayStationInfo> allStations = new ArrayList<>();
		int start = 1;

		while (true) {
			int end = start + PAGE_SIZE - 1;
			URI uri = URI.create(seoulBaseApiUrl + "/" + masterKey
					+ "/json/subwayStationMaster/" + start + "/" + end + "/");

			SubwayStationMasterResponse response = callMasterApi(uri);
			if (response == null || !response.isSuccess()) {
				break;
			}

			List<SubwayStationMasterItem> items = response.getItems();
			if (items.isEmpty()) {
				break;
			}

			items.stream()
					.map(this::toStationInfo)
					.forEach(allStations::add);

			log.info("[SubwayMaster] Fetched {}/{} stations", allStations.size(), response.getTotalCount());

			if (allStations.size() >= response.getTotalCount()) {
				break;
			}
			start = end + 1;
		}

		log.info("[SubwayMaster] Total {} stations fetched", allStations.size());
		return allStations;
	}

	@Override
	public List<SubwayArrivalInfo> getRealtimeArrival(String stationName) {
		URI uri = URI.create(baseUrl + "/" + apiKey
				+ "/json/realtimeStationArrival/0/100/" + stationName);
		return callArrivalApi(uri).stream()
				.map(this::toArrivalInfo)
				.toList();
	}

	@Override
	public List<SubwaySegmentInfo> getStationDistance() {
		List<StationDistanceItem> allItems = new ArrayList<>();
		int start = 1;

		while (true) {
			int end = start + PAGE_SIZE - 1;
			URI uri = URI.create(seoulBaseApiUrl + "/" + routeKey
					+ "/json/StationDstncReqreTimeHm/" + start + "/" + end + "/");

			StationDistanceResponse response = callDistanceApi(uri);
			if (response == null || !response.isSuccess()) {
				break;
			}

			List<StationDistanceItem> items = response.getItems();
			if (items.isEmpty()) {
				break;
			}

			allItems.addAll(items);
			log.info("[SubwaySegment] Fetched {}/{} rows", allItems.size(), response.getTotalCount());

			if (allItems.size() >= response.getTotalCount()) {
				break;
			}
			start = end + 1;
		}

		List<SubwaySegmentInfo> segments = toSegments(allItems);
		log.info("[SubwaySegment] Total {} segments created from {} rows", segments.size(), allItems.size());
		return segments;
	}

	/** 연속된 두 행을 짝지어 구간(segment)으로 변환. 호선이 바뀌면 새 기점. */
	private List<SubwaySegmentInfo> toSegments(List<StationDistanceItem> items) {
		List<SubwaySegmentInfo> segments = new ArrayList<>();
		int seq = 0;

		for (int i = 1; i < items.size(); i++) {
			StationDistanceItem prev = items.get(i - 1);
			StationDistanceItem curr = items.get(i);

			if (!prev.getRouteLine().equals(curr.getRouteLine())) {
				seq = 0;
				continue;
			}

			seq++;
			String lineId = toLineId(curr.getRouteLine() + "호선");
			segments.add(new SubwaySegmentInfo(
					lineId,
					prev.getStationName(),
					curr.getStationName(),
					parseDouble(curr.getDistKm()),
					parseHmToSeconds(curr.getHm()),
					seq
			));
		}

		return segments;
	}

	/** "2:00" → 120.0, "1:30" → 90.0 */
	private Double parseHmToSeconds(String hm) {
		if (hm == null || hm.isBlank()) return null;
		try {
			String[] parts = hm.split(":");
			int minutes = Integer.parseInt(parts[0]);
			int seconds = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			return (double) (minutes * 60 + seconds);
		} catch (Exception e) {
			return null;
		}
	}

	// ===== Infra DTO → App DTO 변환 =====

	private SubwayStationInfo toStationInfo(SubwayStationMasterItem item) {
		String route = item.getRoute();
		return new SubwayStationInfo(
				item.getBldnId(),
				item.getBldnNm(),
				toLineId(route),
				route,
				parseDouble(item.getLat()),
				parseDouble(item.getLot())
		);
	}

	private SubwayArrivalInfo toArrivalInfo(SubwayArrivalItem item) {
		return new SubwayArrivalInfo(
				item.getStatnId(),
				item.getStatnNm(),
				item.getSubwayId(),
				item.getUpdnLine(),
				parseInteger(item.getBarvlDt()),
				item.getBtrainNo(),
				item.getBstatnNm(),
				item.getArvlMsg2(),
				item.getArvlCd(),
				item.getSubwayId(),
				item.getArvlMsg3(),
				item.getRecptnDt(),
				item.getTrainLineNm()
		);
	}

	/** "01호선" → "1001", "02호선" → "1002", ... "09호선" → "1009" */
	private String toLineId(String route) {
		if (route == null) return null;
		String num = route.replaceAll("[^0-9]", "");
		if (num.isEmpty()) return route;
		return String.valueOf(1000 + Integer.parseInt(num));
	}

	// ===== API 호출 =====

	private SubwayStationMasterResponse callMasterApi(URI uri) {
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				SubwayStationMasterResponse response = restTemplate.getForObject(uri, SubwayStationMasterResponse.class);
				if (response == null) {
					log.warn("Empty response from subway master API");
					return null;
				}
				if (!response.isSuccess()) {
					log.warn("Subway master API error: {}",
							response.getData() != null && response.getData().getResult() != null
									? response.getData().getResult().getMessage() : "UNKNOWN");
					return null;
				}
				return response;
			} catch (Exception e) {
				log.warn("Subway master API attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
				if (attempt < MAX_RETRIES) {
					try {
						Thread.sleep((long) Math.pow(2, attempt) * 1000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Retry interrupted", ie);
					}
				}
			}
		}
		log.error("Subway master API failed after {} retries", MAX_RETRIES);
		return null;
	}

	private StationDistanceResponse callDistanceApi(URI uri) {
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				StationDistanceResponse response = restTemplate.getForObject(uri, StationDistanceResponse.class);
				if (response == null) {
					log.warn("Empty response from station distance API");
					return null;
				}
				if (!response.isSuccess()) {
					log.warn("Station distance API error: {}",
							response.getData() != null && response.getData().getResult() != null
									? response.getData().getResult().getMessage() : "UNKNOWN");
					return null;
				}
				return response;
			} catch (Exception e) {
				log.warn("Station distance API attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
				if (attempt < MAX_RETRIES) {
					try {
						Thread.sleep((long) Math.pow(2, attempt) * 1000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Retry interrupted", ie);
					}
				}
			}
		}
		log.error("Station distance API failed after {} retries", MAX_RETRIES);
		return null;
	}

	private List<SubwayArrivalItem> callArrivalApi(URI uri) {
		Exception lastException = null;

		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				SubwayApiResponse response = restTemplate.getForObject(uri, SubwayApiResponse.class);
				if (response == null) {
					log.warn("Empty response from subway API: {}", uri.getPath());
					return List.of();
				}

				if (!response.isSuccess()) {
					log.warn("Subway API error [{}]: {} - {}",
							uri.getPath(),
							response.getErrorMessage() != null ? response.getErrorMessage().getCode() : "UNKNOWN",
							response.getErrorMessage() != null ? response.getErrorMessage().getMessage() : "UNKNOWN");
					return List.of();
				}

				return response.getItems();
			} catch (Exception e) {
				lastException = e;
				log.warn("Subway API call attempt {}/{} failed: {}",
						attempt, MAX_RETRIES, e.getMessage());

				if (attempt < MAX_RETRIES) {
					try {
						Thread.sleep((long) Math.pow(2, attempt) * 1000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						throw new RuntimeException("Retry interrupted", ie);
					}
				}
			}
		}

		log.error("Subway API call failed after {} retries", MAX_RETRIES, lastException);
		return List.of();
	}
}
