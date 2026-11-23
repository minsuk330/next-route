package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/** 카카오 로컬 주소 검색({@code /v2/local/search/address.json}) 응답. */
@JsonIgnoreProperties(ignoreUnknown = true)
record KakaoAddressSearchResponse(List<Document> documents) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Document(
            @JsonProperty("address_name") String addressName,
            /** REGION, ROAD(지역·도로 이름만 맞음), REGION_ADDR, ROAD_ADDR(건물 번호까지 맞음). */
            @JsonProperty("address_type") String addressType,
            String x,
            String y,
            Address address,
            @JsonProperty("road_address") RoadAddress roadAddress
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Address(@JsonProperty("address_name") String addressName) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RoadAddress(
            @JsonProperty("address_name") String addressName,
            @JsonProperty("building_name") String buildingName
    ) {
    }
}
