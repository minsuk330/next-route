package watoo.grd.nextroute.infrastructure.adapter.out.api.tmap.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

/**
 * TMAP 보행자 경로 안내의 GeoJSON FeatureCollection 응답.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TmapPedestrianResponse {
    private String type;                 // "FeatureCollection"
    private List<TmapFeature> features;
}
