package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/** serving POST /predict 응답 바디. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlPredictResponseDto(List<Result> results) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            @JsonProperty("request_id") String requestId,
            String status,
            @JsonProperty("seconds_to_arrival") Double secondsToArrival,
            @JsonProperty("model_version") String modelVersion
    ) {
    }
}
