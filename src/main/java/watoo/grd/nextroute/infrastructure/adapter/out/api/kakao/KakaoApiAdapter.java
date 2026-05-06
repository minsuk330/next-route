package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import watoo.grd.nextroute.application.kakao.dto.KakaoSearchResult;
import watoo.grd.nextroute.application.kakao.port.out.KakaoApiPort;

@Component
@Slf4j
@RequiredArgsConstructor
public class KakaoApiAdapter implements KakaoApiPort {

    @Value("${kakao.api.key}")
    private String apiKey;

    private final RestTemplate restTemplate;

    @Override
    public KakaoSearchResult searchSubwayStation(String query) {
        URI uri = UriComponentsBuilder
                .fromUriString("https://dapi.kakao.com/v2/local/search/keyword.json")
                .queryParam("query", query)
                .queryParam("category_group_code", "SW8")
                .build()
                .toUri();

        KakaoSearchResponse response = callApi(uri);
        if (response == null || response.getDocuments() == null) {
            return new KakaoSearchResult(Collections.emptyList());
        }

        List<KakaoSearchResult.Place> places = response.getDocuments().stream()
                .map(doc -> {
                    double x = parseDouble(doc.getX());
                    double y = parseDouble(doc.getY());
                    return new KakaoSearchResult.Place(doc.getId(), doc.getPlaceName(), x, y);
                })
                .toList();

        return new KakaoSearchResult(places);
    }

    private KakaoSearchResponse callApi(URI uri) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "KakaoAK " + apiKey);
        HttpEntity<Void> entity = new HttpEntity<>(headers);
        try {
            ResponseEntity<KakaoSearchResponse> response =
                    restTemplate.exchange(uri, HttpMethod.GET, entity, KakaoSearchResponse.class);
            return response.getBody();
        } catch (Exception e) {
            log.warn("[Kakao] API call failed: {}", e.getMessage());
            return null;
        }
    }

    private double parseDouble(String value) {
        try {
            return value != null ? Double.parseDouble(value) : 0.0;
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}
