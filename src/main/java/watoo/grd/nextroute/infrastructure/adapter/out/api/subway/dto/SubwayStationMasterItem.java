package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayStationMasterItem {

	@JsonProperty("BLDN_ID")
	private String bldnId;

	@JsonProperty("BLDN_NM")
	private String bldnNm;

	@JsonProperty("ROUTE")
	private String route;

	@JsonProperty("LAT")
	private String lat;

	@JsonProperty("LOT")
	private String lot;
}
