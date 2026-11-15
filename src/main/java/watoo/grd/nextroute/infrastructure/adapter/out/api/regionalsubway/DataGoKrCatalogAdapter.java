package watoo.grd.nextroute.infrastructure.adapter.out.api.regionalsubway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.regionalsubway.port.out.PublicDataCatalogPort;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

/** data.go.kr 데이터셋 카탈로그(schema.org JSON). 키 없음. 예 {@code /catalog/15111497/fileData.json → dateModified "2026-11-02"}. */
@Slf4j
@Component
public class DataGoKrCatalogAdapter implements PublicDataCatalogPort {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public DataGoKrCatalogAdapter(RestClient restClient, ObjectMapper objectMapper,
                                  @Value("${regional-subway.timetable.catalog-base-url:https://www.data.go.kr/catalog}") String baseUrl) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
    }

    @Override
    public Optional<LocalDate> fileDataModified(String datasetId) {
        try {
            String body = restClient.get().uri(URI.create(baseUrl + "/" + datasetId + "/fileData.json"))
                    .exchange((req, res) -> new String(res.getBody().readAllBytes(), StandardCharsets.UTF_8));
            JsonNode root = objectMapper.readTree(body);
            String modified = root.path("dateModified").asText("");
            return modified.isBlank() ? Optional.empty() : Optional.of(LocalDate.parse(modified.substring(0, 10)));
        } catch (Exception e) {
            log.warn("[RegionalSubwayDrift] catalog {} read failed: {}", datasetId, e.getMessage());
            return Optional.empty();
        }
    }
}
