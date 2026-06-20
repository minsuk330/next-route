package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.port.out.MlSupportedRoutesPort;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto.MlMetadataResponseDto;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * ML serving /metadata 호출로 지원 route_id 집합을 가져오는 어댑터.
 * enabled=false 또는 장애 시 Optional.empty() — 호출측이 기존 캐시를 유지한다.
 */
@Slf4j
@Component
public class MlSupportedRoutesAdapter implements MlSupportedRoutesPort {

    private static final String PATH = "/metadata";

    private final RestClient restClient;
    private final MlPredictorProperties properties;

    public MlSupportedRoutesAdapter(
            @Qualifier("mlPredictorRestClient") RestClient restClient,
            MlPredictorProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public Optional<Set<String>> fetchSupportedRouteIds() {
        if (!properties.isEnabled()) {
            return Optional.empty();
        }
        try {
            MlMetadataResponseDto body = restClient.get()
                    .uri(properties.getBaseUrl() + PATH)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(MlMetadataResponseDto.class);
            if (body == null || body.routeCategories() == null) {
                log.warn("[PredictionSupport] /metadata returned no route_categories");
                return Optional.empty();
            }
            return Optional.of(new LinkedHashSet<>(body.routeCategories()));
        } catch (Exception e) {
            log.warn("[PredictionSupport] /metadata fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
