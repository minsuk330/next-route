package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayPathInfo {
    // 도시내 공통
    private Integer totalTime;
    private Integer payment;
    private Integer totalWalk;
    private Double trafficDistance;
    private Double totalDistance;
    private Integer busTransitCount;
    private Integer subwayTransitCount;
    @JsonProperty("mapObj")
    private String mapObj;
    private String firstStartStation;
    private String lastEndStation;
    private Integer totalStationCount;
    private Integer busStationCount;
    private Integer subwayStationCount;
    private Integer checkIntervalTime;
    private String checkIntervalTimeOverYn;
    private Integer totalIntervalTime;
    // 도시간
    private Integer totalPayment;
    private Integer transitCount;
}
