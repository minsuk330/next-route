package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

import java.util.List;
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
