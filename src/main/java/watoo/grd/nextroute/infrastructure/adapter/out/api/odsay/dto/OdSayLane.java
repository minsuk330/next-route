package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayLane {
    // 지하철
    private String name;
    private Integer subwayCode;
    private Integer subwayCityCode;
    // 버스
    private String busNo;
    private Integer type;
    private Integer busID;
    private String busLocalBlID;
    private Integer busCityCode;
    private Integer busProviderCode;
}
