package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TagoTimetableItem {

	private String subwayStationId;
	private String subwayStationNm;
	private String subwayRouteId;
	private String endSubwayStationId;
	private String endSubwayStationNm;
	private String depTime;
	private String arrTime;
	private String dailyTypeCode;
	private String upDownTypeCode;
}
