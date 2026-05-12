package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * Point/LineString feature 공용 properties. 둘 다 모든 필드를 채우지는 않는다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmapFeatureProperties {

    // 공통
    private Integer index;
    private String name;
    private String description;
    private String facilityType;
    private String facilityName;

    // Point properties
    private Integer pointIndex;
    private String pointType;       // SP / EP / PP / GP
    private Integer turnType;
    private String direction;
    private String intersectionName;
    private String nearPoiName;
    private String nearPoiX;
    private String nearPoiY;
    private Integer totalDistance;  // SP 한정
    private Integer totalTime;      // SP 한정

    // LineString properties
    private Integer lineIndex;
    private Integer distance;
    private Integer time;
    private Integer roadType;
    private Integer categoryRoadType;
}
