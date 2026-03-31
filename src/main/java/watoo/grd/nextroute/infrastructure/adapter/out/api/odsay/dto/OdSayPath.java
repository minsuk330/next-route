package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OdSayPath {
    private Integer pathType;
    private OdSayPathInfo info;
    private List<OdSaySubPath> subPath;
}
