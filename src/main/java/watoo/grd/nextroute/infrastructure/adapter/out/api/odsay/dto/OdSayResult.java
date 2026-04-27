package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayResult {
    private Integer searchType;
    private Integer outTrafficCheck;
    // 도시내
    private Integer busCount;
    private Integer subwayCount;
    private Integer subwayBusCount;
    private Double pointDistance;
    private Integer startRadius;
    private Integer endRadius;
    // 도시간
    private Integer trainCount;
    private Integer airCount;
    private Integer mixedCount;

    private List<OdSayPath> path;
}
