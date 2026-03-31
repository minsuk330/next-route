package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSaySubPath {
    private Integer trafficType;
    private Integer sectionTime;
    private Integer distance;
    private String startName;
    private String endName;
    private Double startX;
    private Double startY;
    private Double endX;
    private Double endY;
    private List<OdSayLane> lane;
    private OdSayPassStopList passStopList;
}
