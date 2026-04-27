package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayStation {
    private Integer index;
    private String stationID;
    private String stationName;
    private Integer stationCityCode;
    private Integer stationProviderCode;
    private String localStationID;
    private String arsID;
    private String x;
    private String y;
    private String isNonStop;
}
