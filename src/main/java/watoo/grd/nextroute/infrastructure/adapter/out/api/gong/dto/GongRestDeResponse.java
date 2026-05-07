package watoo.grd.nextroute.infrastructure.adapter.out.api.gong.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import java.util.List;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JacksonXmlRootElement(localName = "response")
public class GongRestDeResponse {

    private Header header;
    private Body body;

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
        private int numOfRows;
        private int pageNo;
        private int totalCount;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Items {
        @JacksonXmlElementWrapper(useWrapping = false)
        @JacksonXmlProperty(localName = "item")
        private List<Item> item;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Item {
        private String locdate;   // YYYYMMDD
        private String dateName;
        private String isHoliday; // Y/N
        private String dateKind;
        private String seq;
    }

    public boolean isSuccess() {
        return header != null && "00".equals(header.getResultCode());
    }

    public List<Item> getItems() {
        if (body == null || body.getItems() == null || body.getItems().getItem() == null) {
            return List.of();
        }
        return body.getItems().getItem();
    }
}
