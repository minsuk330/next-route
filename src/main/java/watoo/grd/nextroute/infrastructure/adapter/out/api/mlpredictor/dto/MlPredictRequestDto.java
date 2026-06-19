package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/** serving POST /predict 요청 바디. */
public record MlPredictRequestDto(List<Item> items) {

    public record Item(
            @JsonProperty("request_id") String requestId,
            Map<String, Object> features
    ) {
    }
}
