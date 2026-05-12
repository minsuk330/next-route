package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * GeoJSON geometry. Point는 [lng,lat], LineString은 [[lng,lat], ...]
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmapGeometry {
    private String type;          // Point | LineString
    private JsonNode coordinates; // 형태가 다르므로 JsonNode로 받음
}
