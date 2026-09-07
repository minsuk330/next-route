package watoo.grd.nextroute.infrastructure.adapter.out.api.naver;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
record NaverLocalSearchResponse(List<Item> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Item(
            String title,
            String category,
            String address,
            String roadAddress,
            String mapx,
            String mapy
    ) {
    }
}
