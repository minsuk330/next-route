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

/**
 * 서울 버스 API 호출 어댑터.
 * 데이터 출처: 공공데이터포털(data.go.kr) - 경기/서울 노선버스 정보 (bus.go.kr)
 * XML 응답을 파싱하여 application 레벨 DTO로 변환한다.
 */
@Slf4j
@Component
public class SeoulBusApiAdapter implements BusApiPort {

	private static final int MAX_RETRIES = 3;

	private final RestTemplate restTemplate;
	private final XmlMapper xmlMapper;
	private final String apiKey;
	private final String baseUrl;

	private final String busRouteKey;
	private final String busRouteBaseUrl;

	public SeoulBusApiAdapter(
			RestTemplate restTemplate,
			@Value("${seoul.api.bus-key}") String apiKey,
			@Value("${seoul.api.bus-base-url}") String baseUrl,
			@Value("${seoul.api.bus-route-key}") String busRouteKey,
			@Value("${seoul.api.seoul-base-api-url}") String busRouteBaseUrl) {
		this.restTemplate = restTemplate;
		this.apiKey = apiKey;
		this.baseUrl = baseUrl;
		this.busRouteKey = busRouteKey;
		this.busRouteBaseUrl = busRouteBaseUrl;
		this.xmlMapper = new XmlMapper();
		this.xmlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
		this.xmlMapper.configure(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY, true);
	}

	/** 키워드로 버스 노선 목록 검색 (예: "360" → 360번 노선 정보)
	 *  [공공데이터포털] 노선번호 목록 조회
	 *  API: GET /busRouteInfo/getBusRouteList?strSrch={keyword} */
	@Override
	public List<BusRouteInfo> getBusRouteList(String searchKeyword) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getBusRouteList"
				+ "?serviceKey=" + apiKey
				+ "&strSrch=" + searchKeyword);
		return callApi(uri, BusRouteItem.class).stream()
				.map(this::toRouteInfo)
				.toList();
	}

	/** 노선 ID로 상세정보 조회 (배차간격, 첫/막차 시간 등)
	 *  [공공데이터포털] 노선 기본정보 조회
	 *  API: GET /busRouteInfo/getRouteInfo?busRouteId={id} */
	@Override
	public List<BusRouteInfo> getRouteInfo(String busRouteId) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getRouteInfo"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusRouteItem.class).stream()
				.map(this::toRouteInfo)
				.toList();
	}

	/** 노선별 경유 정류소 목록 조회 (좌표, 구간거리 포함 → 그래프 엣지 구성용)
	 *  [공공데이터포털] 노선별 경유정류소 조회
	 *  API: GET /busRouteInfo/getStaionByRoute?busRouteId={id} */
	@Override
	public List<BusRouteStopInfo> getStationByRoute(String busRouteId) {
		URI uri = URI.create(baseUrl + "/busRouteInfo/getStaionByRoute"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusRouteStopItem.class).stream()
				.map(this::toRouteStopInfo)
				.toList();
	}

	/** 노선 전체 정류소별 도착예정정보 수집 (구간소요시간, 도착예상시간, 차량ID 포함)
	 *  [공공데이터포털] 경유노선전체 정류소별 도착예정정보
	 *  API: GET /arrive/getArrInfoByRouteAll?busRouteId={id} */
	@Override
	public List<BusArrivalInfo> getArrInfoByRouteAll(String busRouteId) {
		URI uri = URI.create(baseUrl + "/arrive/getArrInfoByRouteAll"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusArrivalItem.class).stream()
				.map(this::toArrivalInfo)
				.toList();
	}

	/** 노선 운행 차량의 실시간 GPS 위치 수집 (구간소요시간 역산용)
	 *  [공공데이터포털] 노선버스 위치정보
	 *  API: GET /buspos/getBusPosByRtid?busRouteId={id} */
	@Override
	public List<BusPositionInfo> getBusPosByRtid(String busRouteId) {
		URI uri = URI.create(baseUrl + "/buspos/getBusPosByRtid"
				+ "?serviceKey=" + apiKey
				+ "&busRouteId=" + busRouteId);
		return callApi(uri, BusPositionItem.class).stream()
				.map(this::toPositionInfo)
				.toList();
	}

	/** 서울 전체 버스 노선 ID 목록 조회
	 *  [서울열린데이터광장] busRoute — 노선 마스터
	 *  API: GET http://openapi.seoul.go.kr:8088/{key}/xml/busRoute/{start}/{end}/ */
	@Override
	public List<String> getBusRouteIds() {
		URI uri = URI.create(busRouteBaseUrl + "/" + busRouteKey + "/xml/busRoute/1/1000/");
		String xml = restTemplate.getForObject(uri, String.class);
		if (xml == null || xml.isBlank()) {
			log.warn("Empty response from Seoul busRoute API");
			return List.of();
		}

		try {
			SeoulBusRouteResponse response = xmlMapper.readValue(xml, SeoulBusRouteResponse.class);
			if (!response.isSuccess()) {
				log.warn("Seoul busRoute API error: {} - {}",
						response.getResult().getCode(), response.getResult().getMessage());
				return List.of();
			}

			List<String> routeIds = response.getRows().stream()
					.map(SeoulBusRouteResponse.Row::getRouteId)
					.toList();
			log.info("[StaticData] Fetched {} bus route IDs from Seoul Open API", routeIds.size());
			return routeIds;
		} catch (Exception e) {
			log.error("Failed to parse Seoul busRoute API response: {}", e.getMessage());
			return List.of();
		}
	}

  // ===== Infra DTO → App DTO 변환 =====

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
				parseDouble(item.getLength()),
				item.getLastBusYn(),
				item.getFirstLowTm(),
				item.getLastLowTm()
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
				item.getTransYn(),
				item.getStationNo(),
				item.getBeginTm(),
				item.getLastTm(),
				item.getTrnstnid(),
				parseDouble(item.getSectSpd())
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
				item.getPlainNo2(),
				item.getArrmsg1(),
				item.getArrmsg2(),
				parseInteger(item.getSectOrd1()),
				parseInteger(item.getSectOrd2()),
				item.getStationNm1(),
				item.getStationNm2()
		);
	}

	private BusPositionInfo toPositionInfo(BusPositionItem item) {
		return new BusPositionInfo(
				item.getVehId(),
				parseDouble(item.getTmY()),
				parseDouble(item.getTmX()),
				parseInteger(item.getStOrd()),
				parseDouble(item.getSectSpd()),
				parseInteger(item.getSectOrd()),
				item.getStopFlag(),
				item.getDataTm(),
				item.getPlainNo(),
				parseInteger(item.getBusType()),
				item.getLastStnId(),
				item.getIsrunyn()
		);
	}

	/**
	 * 공통 API 호출 메서드.
	 * XML 응답을 파싱하고, 실패 시 지수 백오프(2s, 4s, 8s)로 최대 3회 재시도한다.
	 * 모든 재시도 실패 시 빈 리스트를 반환한다.
	 */
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
