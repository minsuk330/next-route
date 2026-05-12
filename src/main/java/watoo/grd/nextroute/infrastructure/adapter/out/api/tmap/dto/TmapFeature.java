package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmapFeature {
    private String type;                    // "Feature"
    private TmapGeometry geometry;
    private TmapFeatureProperties properties;
}
