package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.SubwayApiResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.SubwayArrivalItem;

import java.net.URI;
import java.util.List;

import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

@Slf4j
@Component
public class SeoulSubwayApiAdapter implements SubwayApiPort {

	private static final int MAX_RETRIES = 3;

	private final RestTemplate restTemplate;
	private final String apiKey;
	private final String baseUrl;

	public SeoulSubwayApiAdapter(
			RestTemplate restTemplate,
			@Value("${seoul.api.subway-arrival-key}") String apiKey,
			@Value("${seoul.api.subway-base-url}") String baseUrl) {
		this.restTemplate = restTemplate;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
	}

	@Override
	public List<SubwayArrivalInfo> getRealtimeArrival(String stationName) {
		URI uri = URI.create(baseUrl + "/" + apiKey
				+ "/json/realtimeStationArrival/0/100/" + stationName);
		return callApi(uri).stream()
				.map(this::toArrivalInfo)
				.toList();
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
				item.getArvlMsg3(),
				item.getArvlCd()
		);
	}

	private List<SubwayArrivalItem> callApi(URI uri) {
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
