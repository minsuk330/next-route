package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;
import watoo.grd.nextroute.application.place.port.out.KakaoLocalSearchPort;

@Component
public class KakaoLocalSearchAdapter implements KakaoLocalSearchPort {

    private static final String KEYWORD_PATH = "/v2/local/search/keyword.json";
    private static final String ADDRESS_PATH = "/v2/local/search/address.json";
    /** 건물 번호까지 맞은 결과만 쓴다. REGION·ROAD 는 "역삼동", "테헤란로" 같은 넓은 이름이라 한 지점이 아니다. */
    private static final Set<String> EXACT_ADDRESS_TYPES = Set.of("ROAD_ADDR", "REGION_ADDR");
    /** 주소 결과의 category. 앱은 category 를 그대로 보여준다. */
    static final String ADDRESS_CATEGORY = "주소";
    static final String ADDRESS_KEY_PREFIX = "KAKAO_ADDRESS:";
    /** 첫 결과가 REGION·ROAD 여도 뒤의 정확한 주소를 고를 수 있게 몇 개 받는다. */
    private static final int ADDRESS_CANDIDATES = 3;
    private static final String NO_ADDRESS = "주소 정보 없음";

    private final RestClient restClient;
    private final String apiKey;
    private final String baseUrl;

    public KakaoLocalSearchAdapter(
            RestClient restClient,
            @Value("${kakao.api.key}") String apiKey,
            @Value("${kakao.api.local-base-url:https://dapi.kakao.com}") String baseUrl) {
        this.restClient = restClient;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
    }

    @Override
    public List<PlaceSearchResult.Item> search(String query, int size) {
        requireCredentials();
        try {
            KakaoLocalSearchResponse response = restClient.get()
                    .uri(baseUrl + KEYWORD_PATH, builder -> builder
                            .queryParam("query", query)
                            .queryParam("size", size)
                            .queryParam("page", 1)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(KakaoLocalSearchResponse.class);

            if (response == null || response.documents() == null) {
                return List.of();
            }
            return response.documents().stream()
                    .map(this::toPlace)
                    .filter(item -> item.latitude() >= -90 && item.latitude() <= 90)
                    .filter(item -> item.longitude() >= -180 && item.longitude() <= 180)
                    .toList();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "장소 검색 제공자에 연결하지 못했어요.", exception);
        }
    }

    @Override
    public Optional<PlaceSearchResult.Item> searchAddress(String query) {
        requireCredentials();
        try {
            KakaoAddressSearchResponse response = restClient.get()
                    .uri(baseUrl + ADDRESS_PATH, builder -> builder
                            .queryParam("query", query)
                            .queryParam("size", ADDRESS_CANDIDATES)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "KakaoAK " + apiKey)
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .retrieve()
                    .body(KakaoAddressSearchResponse.class);

            if (response == null || response.documents() == null) {
                return Optional.empty();
            }
            return response.documents().stream()
                    .filter(doc -> EXACT_ADDRESS_TYPES.contains(doc.addressType()))
                    .map(this::toAddressPlace)
                    .filter(item -> item.latitude() >= -90 && item.latitude() <= 90)
                    .filter(item -> item.longitude() >= -180 && item.longitude() <= 180)
                    .findFirst();
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "주소 검색 제공자에 연결하지 못했어요.", exception);
        }
    }

    private PlaceSearchResult.Item toAddressPlace(KakaoAddressSearchResponse.Document doc) {
        String roadAddress = doc.roadAddress() != null && StringUtils.hasText(doc.roadAddress().addressName())
                ? doc.roadAddress().addressName().trim()
                : null;
        String jibunAddress = doc.address() != null && StringUtils.hasText(doc.address().addressName())
                ? doc.address().addressName().trim()
                : safe(doc.addressName());
        String address = roadAddress != null ? roadAddress : (jibunAddress.isEmpty() ? NO_ADDRESS : jibunAddress);
        String buildingName = doc.roadAddress() != null ? safe(doc.roadAddress().buildingName()) : "";
        String name = buildingName.isEmpty() ? address : buildingName;
        return new PlaceSearchResult.Item(
                ADDRESS_KEY_PREFIX + address,
                name,
                address,
                roadAddress,
                parseCoordinate(doc.y()),
                parseCoordinate(doc.x()),
                ADDRESS_CATEGORY,
                List.of("KAKAO")
        );
    }

    private PlaceSearchResult.Item toPlace(KakaoLocalSearchResponse.Document doc) {
        String name = safe(doc.placeName());
        String roadAddress = StringUtils.hasText(doc.roadAddressName()) ? doc.roadAddressName().trim() : null;
        String address = roadAddress != null
                ? roadAddress
                : (StringUtils.hasText(doc.addressName()) ? doc.addressName().trim() : NO_ADDRESS);
        double longitude = parseCoordinate(doc.x());
        double latitude = parseCoordinate(doc.y());
        String key = "KAKAO:" + doc.id();
        return new PlaceSearchResult.Item(
                key,
                name,
                address,
                roadAddress,
                latitude,
                longitude,
                safe(doc.categoryName()),
                List.of("KAKAO")
        );
    }

    private void requireCredentials() {
        if (!StringUtils.hasText(apiKey)) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "장소 검색이 아직 설정되지 않았어요.");
        }
    }

    private static String safe(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private static double parseCoordinate(String value) {
        if (!StringUtils.hasText(value)) return Double.NaN;
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }
}
