package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSaySubPath {
    private Integer trafficType;
    private Double distance;
    private Integer sectionTime;
    private Integer stationCount;
    private List<OdSayLane> lane;
    private Integer intervalTime;
    // 도시내 - 승하차 정류장
    private String startName;
    private Integer startID;
    private Integer startStationCityCode;
    private Integer startStationProviderCode;
    private String startLocalStationID;
    private String startArsID;
    private Double startX;
    private Double startY;
    private String endName;
    private Integer endID;
    private Integer endStationCityCode;
    private Integer endStationProviderCode;
    private String endLocalStationID;
    private String endArsID;
    private Double endX;
    private Double endY;
    // 도시내 지하철 전용
    private String way;
    private Integer wayCode;
    private String door;
    private String startExitNo;
    private Double startExitX;
    private Double startExitY;
    private String endExitNo;
    private Double endExitX;
    private Double endExitY;
    private OdSayPassStopList passStopList;
    // 도시간 전용
    private Integer trainType;
    private Integer payment;
    private Integer trainSpSeatPayment;
    private String trainSpSeatYn;
    private Integer intervalCount;
    private Integer startCityCode;
    private Integer endCityCode;
}
