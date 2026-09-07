package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoLocalSearchResponse(List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(
            String id,
            @JsonProperty("place_name") String placeName,
            @JsonProperty("address_name") String addressName,
            @JsonProperty("road_address_name") String roadAddressName,
            @JsonProperty("category_name") String categoryName,
            String x,
            String y
    ) {
    }
}
