package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.bus.dto.*;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto.*;

import java.net.URI;
import java.util.List;

import static watoo.grd.nextroute.common.util.ParseUtils.parseDouble;
import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

@Slf4j
@Component
public class SeoulBusApiAdapter implements BusApiPort {

	private static final int MAX_RETRIES = 3;

	private final RestTemplate restTemplate;
	private final XmlMapper xmlMapper;
	private final String apiKey;
	private final String baseUrl;

	public SeoulBusApiAdapter(
			RestTemplate restTemplate,
			@Value("${seoul.api.bus-key}") String apiKey,
			@Value("${seoul.api.bus-base-url}") String baseUrl) {
		this.restTemplate = restTemplate;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.xmlMapper = new XmlMapper();
		this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		this.xmlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
	}

	@Override
	public List<BusRouteInfo> getBusRouteList(String searchKeyword) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getBusRouteList"
				+ "?serviceKey=" + apiKey
				+ "&strSrch=" + searchKeyword);
		return callApi(uri, BusRouteItem.class).stream()
				.map(this::toRouteInfo)
				.toList();
	}

	@Override
	public List<BusRouteInfo> getRouteInfo(String busRouteId) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getRouteInfo"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusRouteItem.class).stream()
				.map(this::toRouteInfo)
				.toList();
	}

	@Override
	public List<BusRouteStopInfo> getStationByRoute(String busRouteId) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getStaionByRoute"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusRouteStopItem.class).stream()
				.map(this::toRouteStopInfo)
				.toList();
	}

	@Override
	public List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId) {
		URI uri = URI.create(baseUrl + "/arrive/getArrInfoByRouteAll"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusArrivalItem.class).stream()
				.map(this::toArrivalInfo)
				.toList();
	}

	@Override
	public List<BusPositionInfo> getBusPosByRtid(String busRouteId) {
		URI uri = URI.create(baseUrl + "/buspos/getBusPosByRtid"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusPositionItem.class).stream()
				.map(this::toPositionInfo)
				.toList();
	}

	// ===== Infrastructure DTO → Application DTO 변환 =====

	private BusRouteInfo toRouteInfo(BusRouteItem item) {
		return new BusRouteInfo(
				item.getBusRouteId(),
				item.getBusRouteNm(),
				parseInteger(item.getRouteType()),
				item.getStStaNm(),
				item.getEdStaNm(),
				parseInteger(item.getTerm()),
				item.getFirstBusTm(),
				item.getLastBusTm(),
				item.getCorpNm(),
				parseDouble(item.getLength())
		);
	}

	private BusRouteStopInfo toRouteStopInfo(BusRouteStopItem item) {
		return new BusRouteStopInfo(
				item.getBusRouteId(),
				parseInteger(item.getSeq()),
				item.getSection(),
				item.getStation(),
				item.getStationNm(),
				item.getArsId(),
				parseDouble(item.getGpsY()),
				parseDouble(item.getGpsX()),
				parseDouble(item.getFullSectDist()),
				item.getDirection(),
				item.getTransYn()
		);
	}

	private BusArrivalInfo toArrivalInfo(BusArrivalItem item) {
		return new BusArrivalInfo(
				item.getBusRouteId(),
				item.getStId(),
				parseInteger(item.getStaOrd()),
				parseInteger(item.getExps1()),
				parseInteger(item.getTraTime1()),
				parseDouble(item.getTraSpd1()),
				item.getIsArrive1(),
				item.getVehId1(),
				item.getPlainNo1(),
				parseInteger(item.getExps2()),
				parseInteger(item.getTraTime2()),
				parseDouble(item.getTraSpd2()),
				item.getIsArrive2(),
				item.getVehId2(),
				item.getPlainNo2()
		);
	}

	private BusPositionInfo toPositionInfo(BusPositionItem item) {
		return new BusPositionInfo(
				item.getVehId(),
				parseDouble(item.getTmY()),
				parseDouble(item.getTmX()),
				parseInteger(item.getStOrd()),
				parseDouble(item.getSectSpd()),
				parseInteger(item.getSectOrd())
		);
	}

	// ===== API 호출 (재시도 포함) =====

	private <T> List<T> callApi(URI uri, Class<T> itemType) {
		Exception lastException = null;

		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				String xml = restTemplate.getForObject(uri, String.class);
				if (xml == null || xml.isBlank()) {
					log.warn("Empty response from API: {}", uri.getPath());
					return List.of();
				}

				JavaType responseType = xmlMapper.getTypeFactory()
						.constructParametricType(BusApiResponse.class, itemType);
				BusApiResponse<T> response = xmlMapper.readValue(xml, responseType);

				if (!response.isSuccess()) {
					log.warn("API error [{}]: {} - {}",
							uri.getPath(),
							response.getMsgHeader().getHeaderCd(),
							response.getMsgHeader().getHeaderMsg());
					return List.of();
				}

				return response.getItems();
			} catch (Exception e) {
				lastException = e;
				log.warn("API call attempt {}/{} failed [{}]: {}",
						attempt, MAX_RETRIES, uri.getPath(), e.getMessage());

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

		log.error("API call failed after {} retries: {}", MAX_RETRIES, uri.getPath(), lastException);
		return List.of();
	}
}
