package watoo.grd.nextroute.infrastructure.adapter.out.api.kma.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/** 기상청 초단기실황(getUltraSrtNcst) JSON 응답. category/obsrValue 목록. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KmaNcstResponse {

    private Response response;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private Header header;
        private Body body;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Header {
        private String resultCode;
        private String resultMsg;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Body {
        private Items items;
        private int totalCount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Items {
        private List<Item> item;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String category;   // T1H/RN1/REH/WSD/PTY ...
        private String obsrValue;  // 관측값(문자열)
    }

    public boolean isSuccess() {
        return response != null
                && response.getHeader() != null
                && "00".equals(response.getHeader().getResultCode());
    }

    public List<Item> items() {
        if (response == null || response.getBody() == null
                || response.getBody().getItems() == null
                || response.getBody().getItems().getItem() == null) {
            return List.of();
        }
        return response.getBody().getItems().getItem();
    }
}
