package watoo.grd.nextroute.infrastructure.adapter.out.api.naver;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.HtmlUtils;
import watoo.grd.nextroute.application.place.config.NaverApiHubProperties;
import watoo.grd.nextroute.application.place.dto.PlaceSearchResult;
import watoo.grd.nextroute.application.place.port.out.NaverLocalSearchPort;

import static org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE;

@Component
@RequiredArgsConstructor
public class NaverApiHubLocalSearchAdapter implements NaverLocalSearchPort {

    private static final String CLIENT_ID_HEADER = "X-NCP-APIGW-API-KEY-ID";
    private static final String CLIENT_SECRET_HEADER = "X-NCP-APIGW-API-KEY";
    private static final double COORDINATE_SCALE = 10_000_000d;

    private final RestClient restClient;
    private final NaverApiHubProperties properties;

    @Override
    public List<PlaceSearchResult.Item> search(String query, int limit) {
        requireCredentials();
        try {
            NaverLocalSearchResponse response = restClient.get()
                    .uri(properties.getBaseUrl() + "/search/v1/local", builder -> builder
                            .queryParam("query", query)
                            .queryParam("display", limit)
                            .queryParam("start", 1)
                            .queryParam("sort", "random")
                            .queryParam("format", "json")
                            .build())
                    .header(HttpHeaders.ACCEPT, "application/json")
                    .header(CLIENT_ID_HEADER, properties.getClientId())
                    .header(CLIENT_SECRET_HEADER, properties.getClientSecret())
                    .retrieve()
                    .body(NaverLocalSearchResponse.class);

            if (response == null || response.items() == null) {
                return List.of();
            }
            return response.items().stream()
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

    private PlaceSearchResult.Item toPlace(NaverLocalSearchResponse.Item item) {
        String name = clean(item.title());
        String address = firstText(item.roadAddress(), item.address(), "주소 정보 없음");
        String roadAddress = StringUtils.hasText(item.roadAddress()) ? clean(item.roadAddress()) : null;
        double longitude = parseCoordinate(item.mapx(), 180);
        double latitude = parseCoordinate(item.mapy(), 90);
        String keyMaterial = name + '|' + address + '|' + longitude + '|' + latitude;
        String key = "NAVER:" + UUID.nameUUIDFromBytes(keyMaterial.getBytes(StandardCharsets.UTF_8));
        return new PlaceSearchResult.Item(
                key,
                name,
                address,
                roadAddress,
                latitude,
                longitude,
                clean(item.category()),
                List.of("NAVER")
        );
    }

    private void requireCredentials() {
        if (!StringUtils.hasText(properties.getClientId()) || !StringUtils.hasText(properties.getClientSecret())) {
            throw new ResponseStatusException(SERVICE_UNAVAILABLE, "장소 검색이 아직 설정되지 않았어요.");
        }
    }

    private static String firstText(String first, String second, String fallback) {
        if (StringUtils.hasText(first)) return clean(first);
        if (StringUtils.hasText(second)) return clean(second);
        return fallback;
    }

    private static String clean(String value) {
        if (!StringUtils.hasText(value)) return "";
        return HtmlUtils.htmlUnescape(value.replaceAll("<[^>]+>", "")).trim();
    }

    private static double parseCoordinate(String value, double maximum) {
        if (!StringUtils.hasText(value)) return Double.NaN;
        try {
            double parsed = Double.parseDouble(value);
            return Math.abs(parsed) > maximum ? parsed / COORDINATE_SCALE : parsed;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }
}
