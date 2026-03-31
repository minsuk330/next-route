package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayPathInfo {
    private Integer totalTime;
    private Integer payment;
    private Integer totalWalk;
    private Integer totalDistance;
    private Integer busTransitCount;
    private Integer subwayTransitCount;
    private String firstStartStation;
    private String lastEndStation;
}
