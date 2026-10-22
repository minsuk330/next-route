package watoo.grd.nextroute.infrastructure.adapter.out.api.subway;

import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriUtils;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayExpressType;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionInfo;
import watoo.grd.nextroute.application.subway.dto.SubwaySegmentInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayStationInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.port.out.SubwayApiPort;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;
import watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.*;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static watoo.grd.nextroute.common.util.ParseUtils.parseDouble;
import static watoo.grd.nextroute.common.util.ParseUtils.parseInteger;

@Slf4j
@Component
public class SeoulSubwayApiAdapter implements SubwayApiPort {

	private static final int MAX_RETRIES = 3;
	private static final int PAGE_SIZE = 1000;
	/** 이보다 짧은 값은 키로 보지 않는다 — 빈 값·짧은 더미 값이 메시지의 엉뚱한 글자를 지우지 않게. */
	private static final int MIN_SECRET_LENGTH = 8;

	private static final String API_ARRIVAL = "seoul-subway-arrival";
	private static final String API_MASTER = "seoul-subway-master";
	private static final String API_DISTANCE = "seoul-subway-distance";
	/**
	 * 위치 API 차단기는 <b>노선별</b>로 판다.
	 *
	 * <p>{@link ApiCallBlocker} 는 하루 단위로 막는다. 공통 키 하나로 두면 한 노선의 잘못된
	 * 응답이 <b>모든 노선</b>을 그날 내내 막는다.
	 */
	private static final String API_POSITION_PREFIX = "seoul-subway-position:";

	private final RestTemplate restTemplate;
	private final ApiCallBlocker blocker;
	private final String arrivalKey;
  private final String positionKey;
	private final String baseUrl;
	private final String masterKey;
	private final String routeKey;
	private final String seoulBaseApiUrl;
	/**
	 * 로그·예외 메시지에서 가릴 키들. 서울 API 는 키를 URL 경로에 받아({@code /api/subway/{키}/json/…}) 경로를 찍거나
	 * RestTemplate 예외 메시지("I/O error on GET request for …")를 찍으면 키가 그대로 로그(Loki)에 남았다.
	 */
	private final List<String> secrets;

	public SeoulSubwayApiAdapter(
			RestTemplate restTemplate,
			ApiCallBlocker blocker,
			@Value("${seoul.api.subway-arrival-key}") String arrivalKey,
      @Value("${seoul.api.subway-position-key}") String positionKey,
			@Value("${seoul.api.subway-base-url}") String baseUrl,
			@Value("${seoul.api.subway-master-key}") String masterKey,
			@Value("${seoul.api.subway-route-key}") String routeKey,
			@Value("${seoul.api.seoul-base-api-url}") String seoulBaseApiUrl) {
		this.restTemplate = restTemplate;
		this.blocker = blocker;
		this.arrivalKey = arrivalKey;
    this.positionKey = positionKey;
		this.baseUrl = baseUrl;
		this.masterKey = masterKey;
		this.routeKey = routeKey;
		this.seoulBaseApiUrl = seoulBaseApiUrl;
		this.secrets = Stream.of(arrivalKey, positionKey, masterKey, routeKey)
				.filter(key -> key != null && key.length() >= MIN_SECRET_LENGTH)
				.distinct()
				.toList();
	}

	/** 키를 {@code ****} 로 가린다. 로그와 예외 메시지에 URL·원인 예외를 넣을 때는 반드시 이것을 거친다. */
	String redact(Object value) {
		if (value == null) {
			return null;
		}
		String text = String.valueOf(value);
		for (String secret : secrets) {
			text = text.replace(secret, "****");
		}
		return text;
	}

	@Override
	public List<SubwayStationInfo> getSubwayStationMaster() {
		if (blocker.isBlocked(API_MASTER)) return List.of();

		List<SubwayStationInfo> allStations = new ArrayList<>();
		int start = 0;

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
	public List<SubwayArrivalInfo> getRealtimeArrival() {
		if (blocker.isBlocked(API_ARRIVAL)) return List.of();

		List<SubwayArrivalInfo> allArrivals = new ArrayList<>();

			URI uri = URI.create(baseUrl + "/" + arrivalKey
					+ "/json/realtimeStationArrival/" + "ALL");

			List<SubwayArrivalItem> items = callArrivalApi(uri);

			items.stream()
					.map(this::toArrivalInfo)
					.forEach(allArrivals::add);

			//log.info("[SubwayArrival] Fetched {} arrivals so far", allArrivals.size());

		return allArrivals;
	}

	@Override
	public List<SubwayArrivalInfo> getRealtimeArrivalByStation(String stationName) {
		// 역 이름은 반드시 인코딩한다(#427). 날것의 한글을 URI 에 넣으면 HTTP 클라이언트가 요청 줄에서 '?' 로 바꿔
		// 경로가 "/0/1000/" 로 끝나고, 서울 API 는 그것을 ALL 로 답한다 — 역 하나 대신 전 노선 약 3,000행(2MB)을
		// 받아 0.3~0.7초를 쓴다. 우리가 역 id 로 다시 걸러 값은 맞아서 기능 시험으로는 드러나지 않았다.
		URI uri = URI.create(baseUrl + "/" + arrivalKey
				+ "/json/realtimeStationArrival/0/1000/"
				+ UriUtils.encodePathSegment(stationName, StandardCharsets.UTF_8));
		return callArrivalApi(uri).stream()
				.map(this::toArrivalInfo)
				.toList();
	}

	@Override
	public SubwayPositionFetchResult getRealtimePosition(String lineId) {
		Instant fetchedAt = Instant.now();

		Optional<String> lineName = SubwayLineIdMapper.toLineName(lineId);
		if (lineName.isEmpty()) {
			// 호선명을 지어내 부르면 엉뚱한 노선의 열차를 우리 노선인 줄 알고 추적한다.
			log.warn("[SubwayPosition] 호선명을 모르는 line_id — lineId={}", lineId);
			return SubwayPositionFetchResult.failed(lineId, fetchedAt, "UNKNOWN_LINE");
		}
		if (blocker.isBlocked(API_POSITION_PREFIX + lineId)) {
			return SubwayPositionFetchResult.blocked(lineId, fetchedAt);
		}

		URI uri = URI.create(baseUrl + "/" + positionKey + "/json/realtimePosition/0/" + PAGE_SIZE
				+ "/" + URLEncoder.encode(lineName.get(), StandardCharsets.UTF_8));

		SubwayPositionResponse response = callPositionApi(uri, lineId);
		if (response == null) {
			return SubwayPositionFetchResult.failed(lineId, fetchedAt, "CALL_FAILED");
		}
		if (response.isNoData()) {
			// 막차 뒤·첫차 전에는 매일 온다. 오류로 보고 막으면 그 노선이 자정까지 죽는다 —
			// 수집이 04:30 에 시작해 첫차 전에 전 노선이 막히던 게 이것이었다(2026-09-28·29).
			return SubwayPositionFetchResult.noTrains(lineId, fetchedAt);
		}
		if (!response.isSuccess()) {
			blocker.block(API_POSITION_PREFIX + lineId);
			log.warn("[SubwayPosition] 비정상 응답 — lineId={}, code={}, message={}",
					lineId, response.code(), response.message());
			return SubwayPositionFetchResult.failed(lineId, fetchedAt, response.code());
		}

		List<SubwayPositionInfo> rows = response.getItems().stream()
				.map(this::toPositionInfo)
				.toList();
		Integer total = response.total();

		// 받은 건수가 API 가 말한 전체와 다르면 응답이 완결되지 않은 것이다. 그 상태에서 전이를
		// 만들면 "사라진" 열차가 대량으로 생긴다.
		SubwayPositionFetchResult.Status status = (total == null || total == rows.size())
				? SubwayPositionFetchResult.Status.COMPLETE
				: SubwayPositionFetchResult.Status.PARTIAL;
		if (status == SubwayPositionFetchResult.Status.PARTIAL) {
			log.warn("[SubwayPosition] 건수 불일치 — lineId={}, total={}, rows={}",
					lineId, total, rows.size());
		}

		return new SubwayPositionFetchResult(lineId, rows, total, status, fetchedAt, null);
	}

	/**
	 * 위치 응답 한 행을 응용 계층 모양으로 옮긴다.
	 *
	 * <p>방향은 여기서 <b>도착정보와 같은 표기로</b> 맞춘다. 원시 {@code updnLine} 이 밖으로
	 * 나가면 두 API 의 방향 표기가 뒤섞인 채 비교되는 코드가 생긴다.
	 */
	private SubwayPositionInfo toPositionInfo(SubwayPositionItem item) {
		String lineId = item.getSubwayId();
		return new SubwayPositionInfo(
				lineId,
				item.getTrainNo(),
				item.getStatnId(),
				item.getStatnNm(),
				SubwayDirectionNormalizer.fromPositionRow(lineId, item.getUpdnLine(), item.getStatnId(),
						item.getStatnTid(), item.getStatnTnm()),
				item.getStatnTid(),
				item.getStatnTnm(),
				SubwayTrainStatus.from(item.getTrainSttus()),
				SubwayExpressType.from(item.getDirectAt()),
				item.getRecptnDt(),
				item.getLastRecptnDt(),
				// enum 이 9(ITX-청춘)를 UNKNOWN 으로 접기 전 원문(subway-express PR-S3). 열차 종류 분류기가 읽는다
				blankToNull(item.getDirectAt())
		);
	}


	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

  @Override
	public List<SubwaySegmentInfo> getStationDistance() {
		if (blocker.isBlocked(API_DISTANCE)) return List.of();

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
			String lineId = SubwayLineIdMapper.toInternalLineId(curr.getRouteLine() + "호선");
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
				SubwayLineIdMapper.toInternalLineId(route),
				route,
				parseDouble(item.getLat()),
				parseDouble(item.getLot())
		);
	}

	/**
	 * {@code statnFid}/{@code statnTid} 가 서로 바뀌어 오는 노선. 실측(2026-10-22 운영 3회): 공항철도만 {@code trainLineNm} 의
	 * "Y방면"(다음 역)이 {@code statnFid} 쪽이다(67/67, 나머지 18개 노선은 {@code statnTid} 쪽). 전역 출발(3) 행의 열차도 위치 API 로는
	 * {@code statnTid} 역에 있었다(23/23). 그대로 두면 공항철도 열차가 조회역을 이미 지난 것으로 놓이고 구간 소요시간도 반대 구간을 잡는다.
	 */
	private static final Set<String> SWAPPED_NEIGHBOR_LINES = Set.of("1065");

	/**
	 * 도착 API 행선지({@code bstatnNm}) 중 역 이름이 아닌 표기 → 역 이름. 2호선 성수지선의 성수행은 {@code 성수지선} 으로 와서 역 시트가
	 * "성수지선 방면"·"성수지선행"으로 그렸다(2026-10-22 운영). 위치 API 쪽은 종착 역 id 를 역 마스터 이름으로 바꿔 이미 {@code 성수} 다.
	 */
	private static final Map<String, String> DESTINATION_STATION_NAMES = Map.of("성수지선", "성수");

	private SubwayArrivalInfo toArrivalInfo(SubwayArrivalItem item) {
		boolean swapped = SWAPPED_NEIGHBOR_LINES.contains(item.getSubwayId());
		return new SubwayArrivalInfo(
				item.getStatnId(),
				item.getStatnNm(),
				item.getSubwayId(),
				item.getUpdnLine(),
				swapped ? item.getStatnTid() : item.getStatnFid(),
				swapped ? item.getStatnFid() : item.getStatnTid(),
				parseInteger(item.getTrnsitCo()),
				item.getOrdkey(),
				item.getSubwayList(),
				item.getStatnList(),
				item.getBtrainSttus(),
				parseInteger(item.getBarvlDt()),
				item.getBtrainNo(),
				item.getBstatnId(),
				destinationStationName(item.getBstatnNm()),
				item.getArvlMsg2(),
				item.getArvlCd(),
				item.getSubwayId(),
				item.getArvlMsg3(),
				item.getRecptnDt(),
				item.getTrainLineNm(),
				item.getLstcarAt()
		);
	}

	private static String destinationStationName(String destination) {
		return destination == null ? null : DESTINATION_STATION_NAMES.getOrDefault(destination.trim(), destination);
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
					blocker.block(API_MASTER);
					log.warn("Subway master API error: {}",
							response.getData() != null && response.getData().getResult() != null
									? response.getData().getResult().getMessage() : "UNKNOWN");
					return null;
				}
				return response;
			} catch (Exception e) {
				log.warn("Subway master API attempt {}/{} failed: {}", attempt, MAX_RETRIES, redact(e.getMessage()));
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

	/**
	 * 위치 API 를 부른다. 실패해도 <b>예외를 던지지 않고</b> null 을 준다.
	 *
	 * <p>도착정보({@code callArrivalApi})는 재시도 끝에 예외를 던진다. 여기서 같은 방식을 쓰지
	 * 않는 이유는 호출 축이 다르기 때문이다 — 위치는 <b>노선마다</b> 부르므로, 한 노선의 실패가
	 * 예외로 번지면 그 tick 의 나머지 노선까지 통째로 못 받는다. 실패는 값으로 돌려주고
	 * 부르는 쪽이 노선 단위로 판단한다.
	 */
	private SubwayPositionResponse callPositionApi(URI uri, String lineId) {
		for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
			try {
				SubwayPositionResponse response =
						restTemplate.getForObject(uri, SubwayPositionResponse.class);
				if (response == null) {
					log.warn("[SubwayPosition] 빈 응답 — lineId={}", lineId);
					return null;
				}
				return response;
			} catch (Exception e) {
				log.warn("[SubwayPosition] 호출 실패 {}/{} — lineId={}, {}",
						attempt, MAX_RETRIES, lineId, redact(e.getMessage()));
				if (attempt < MAX_RETRIES) {
					try {
						Thread.sleep((long) Math.pow(2, attempt) * 1000);
					} catch (InterruptedException ie) {
						Thread.currentThread().interrupt();
						return null;
					}
				}
			}
		}
		log.error("[SubwayPosition] {}회 재시도 후 실패 — lineId={}", MAX_RETRIES, lineId);
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
					blocker.block(API_DISTANCE);
					log.warn("Station distance API error: {}",
							response.getData() != null && response.getData().getResult() != null
									? response.getData().getResult().getMessage() : "UNKNOWN");
					return null;
				}
				return response;
			} catch (Exception e) {
				log.warn("Station distance API attempt {}/{} failed: {}", attempt, MAX_RETRIES, redact(e.getMessage()));
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
					log.warn("Empty response from subway API: {}", redact(uri.getPath()));
					return List.of();
				}

				if (!response.isSuccess()) {
					String code = response.getErrorMessage() != null ? response.getErrorMessage().getCode() : "UNKNOWN";
					String msg  = response.getErrorMessage() != null ? response.getErrorMessage().getMessage() : "UNKNOWN";
					log.warn("Subway arrival API non-success [{}]: {} - {}", redact(uri.getPath()), code, msg);
					throw new RuntimeException("Subway arrival API error: " + code + " - " + msg);
				}

				return response.getItems();
			} catch (Exception e) {
				lastException = e;
				log.warn("Subway API call attempt {}/{} failed: {}",
						attempt, MAX_RETRIES, redact(e.getMessage()));

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

		// 원인 예외를 붙이지 않는다 — 그 메시지(와 스택 트레이스)에 키가 든 URL 이 실린다. 가린 문자열로만 남긴다.
		String cause = redact(lastException);
		log.error("Subway arrival API failed after {} retries: {}", MAX_RETRIES, cause);
		throw new RuntimeException("Subway arrival API failed after " + MAX_RETRIES + " retries: " + cause);
	}
}
