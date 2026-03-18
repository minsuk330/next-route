package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusRouteItem {

	private String busRouteId;
	private String busRouteNm;
	private String busRouteAbrv;
	private String length;
	private String routeType;
	private String stStaNm;
	private String edStaNm;
	private String term;
	private String firstBusTm;
	private String lastBusTm;
	private String corpNm;
	private String lastBusYn;
	private String firstLowTm;
	private String lastLowTm;
}
