package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** ML serving /metadata 응답 (지원 노선 목록만 사용). */
public record MlMetadataResponseDto(
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("route_count") Integer routeCount,
        @JsonProperty("route_categories") List<String> routeCategories
) {}
