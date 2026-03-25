package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.subway.dto.SubwayStationTagoInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTimetableInfo;
import watoo.grd.nextroute.application.subway.port.out.TagoSubwayApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.TagoBaseResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.TagoStationItem;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.TagoTimetableItem;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class TagoSubwayApiAdapter implements TagoSubwayApiPort {

	private static final int MAX_RETRIES = 3;
	private static final int PAGE_SIZE = 1000;

	private final RestTemplate restTemplate;
	private final ObjectMapper objectMapper;
	private final String baseUrl;
	private final String apiKey;

	public TagoSubwayApiAdapter(
			RestTemplate restTemplate,
			ObjectMapper objectMapper,
			@Value("${seoul.api.subway-tago-base-url}") String baseUrl,
			@Value("${seoul.api.subway-key}") String apiKey) {
		this.restTemplate = restTemplate;
		this.objectMapper = objectMapper;
		this.baseUrl = baseUrl;
		this.apiKey = apiKey;
	}

	@Override
	public List<SubwayStationTagoInfo> getAllStations() {
		List<SubwayStationTagoInfo> allItems = new ArrayList<>();
		int pageNo = 1;

		while (true) {
			URI uri = URI.create(baseUrl + "/GetKwrdFndSubwaySttnList"
					+ "?serviceKey=" + apiKey
					+ "&pageNo=" + pageNo
					+ "&numOfRows=" + PAGE_SIZE
					+ "&_type=json");

			TagoBaseResponse<TagoStationItem> response = callApi(uri,
					new TypeReference<TagoBaseResponse<TagoStationItem>>() {});

			if (response == null || !response.isSuccess()) {
				log.warn("[TAGO] Station list API failed at page {}", pageNo);
				break;
			}

			List<TagoStationItem> items = response.getItems();
			if (items.isEmpty()) {
				break;
			}

			items.stream()
					.map(item -> new SubwayStationTagoInfo(
							item.getSubwayStationId(),
							item.getSubwayStationName(),
							item.getSubwayRouteName()))
					.forEach(allItems::add);

			log.info("[TAGO] Fetched {}/{} stations", allItems.size(), response.getTotalCount());

			if (allItems.size() >= response.getTotalCount()) {
				break;
			}
			pageNo++;
		}

		log.info("[TAGO] Total {} stations fetched", allItems.size());
		return allItems;
	}

	@Override
	public List<SubwayTimetableInfo> getTimetable(String tagoStationId, String dailyTypeCode, String upDownTypeCode) {
		List<SubwayTimetableInfo> allItems = new ArrayList<>();
		int pageNo = 1;

		while (true) {
			URI uri = URI.create(baseUrl + "/GetSubwaySttnAcctoSchdulList"
					+ "?serviceKey=" + apiKey
					+ "&pageNo=" + pageNo
					+ "&numOfRows=" + PAGE_SIZE
					+ "&_type=json"
					+ "&subwayStationId=" + tagoStationId
					+ "&dailyTypeCode=" + dailyTypeCode
					+ "&upDownTypeCode=" + upDownTypeCode);

			TagoBaseResponse<TagoTimetableItem> response = callApi(uri,
					new TypeReference<TagoBaseResponse<TagoTimetableItem>>() {});

			if (response == null || !response.isSuccess()) {
				log.warn("[TAGO] Timetable API failed for station={}, day={}, dir={}",
						tagoStationId, dailyTypeCode, upDownTypeCode);
				break;
			}

			List<TagoTimetableItem> items = response.getItems();
			if (items.isEmpty()) {
				break;
			}

			items.stream()
					.map(item -> new SubwayTimetableInfo(
							item.getSubwayStationId(),
							item.getSubwayStationNm(),
							item.getSubwayRouteId(),
							item.getEndSubwayStationNm(),
							item.getDepTime(),
							item.getArrTime(),
							item.getDailyTypeCode(),
							item.getUpDownTypeCode()))
					.forEach(allItems::add);

			if (allItems.size() >= response.getTotalCount()) {
				break;
			}
			pageNo++;
		}

		return allItems;
	}

	@SuppressWarnings("unchecked")
	private <T> TagoBaseResponse<T> callApi(URI uri, TypeReference<TagoBaseResponse<T>> typeRef) {
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				// TAGO API returns generic JSON; fetch as Map and convert with ObjectMapper
				Map<String, Object> raw = restTemplate.getForObject(uri, Map.class);
				if (raw == null) {
					log.warn("[TAGO] Empty response: {}", uri.getPath());
					return null;
				}
				return objectMapper.convertValue(raw, typeRef);
			} catch (Exception e) {
				log.warn("[TAGO] API attempt {}/{} failed: {}", attempt, MAX_RETRIES, e.getMessage());
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
		log.error("[TAGO] API call failed after {} retries: {}", MAX_RETRIES, uri.getPath());
		return null;
	}
}
