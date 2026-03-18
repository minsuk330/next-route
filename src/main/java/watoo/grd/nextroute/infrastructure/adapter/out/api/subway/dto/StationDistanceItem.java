package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class StationDistanceItem {

	@JsonProperty("SBWY_ROUT_LN")
	private String routeLine;

	@JsonProperty("SBWY_STNS_NM")
	private String stationName;

	@JsonProperty("HM")
	private String hm;

	@JsonProperty("DIST_KM")
	private String distKm;

	@JsonProperty("ACML_DIST")
	private String acmlDist;
}
