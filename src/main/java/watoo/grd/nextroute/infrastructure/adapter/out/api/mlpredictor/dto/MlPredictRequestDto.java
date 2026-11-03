package watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * serving POST /predict 요청 바디.
 *
 * <p>{@code route_history} 는 rt_* 피처용 노선별 최근 관측(선택). item 의 {@code vehicle_id}·
 * {@code snapshot_at} 이 그 이력 안에서 해당 차량의 현재 관측을 가리킨다. 둘 다 없으면 serving 이
 * rt_* 를 NaN 으로 두고 예측한다.
 */
public record MlPredictRequestDto(
        List<Item> items,
        @JsonProperty("route_history") Map<String, List<Observation>> routeHistory
) {

    public MlPredictRequestDto(List<Item> items) {
        this(items, null);
    }

    public record Item(
            @JsonProperty("request_id") String requestId,
            Map<String, Object> features,
            @JsonProperty("vehicle_id") String vehicleId,
            /** 관측 시각(dataTm), epoch seconds. */
            @JsonProperty("snapshot_at") Long snapshotAt
    ) {
        public Item(String requestId, Map<String, Object> features) {
            this(requestId, features, null, null);
        }
    }

    public record Observation(
            @JsonProperty("vehicle_id") String vehicleId,
            @JsonProperty("section_order") int sectionOrder,
            @JsonProperty("snapshot_at") long snapshotAt
    ) {
    }
}
