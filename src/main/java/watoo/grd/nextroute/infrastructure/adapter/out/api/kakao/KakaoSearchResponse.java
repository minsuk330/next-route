package watoo.grd.nextroute.infrastructure.adapter.out.api.kakao;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KakaoSearchResponse {

    private List<Document> documents;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Document {
        private String id;

        @JsonProperty("place_name")
        private String placeName;

        private String x;
        private String y;
    }
}
