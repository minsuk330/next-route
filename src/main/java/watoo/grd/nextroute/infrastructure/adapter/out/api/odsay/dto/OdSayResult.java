package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayResult {
    private Integer searchType;
    private Integer busCount;
    private Integer subwayCount;
    private List<OdSayPath> path;
}
