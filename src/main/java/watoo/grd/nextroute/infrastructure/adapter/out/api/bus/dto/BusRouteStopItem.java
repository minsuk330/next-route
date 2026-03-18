package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusRouteStopItem {

	private String busRouteId;
	private String seq;
	private String section;
	private String station;
	private String stationNm;
	private String gpsX;
	private String gpsY;
	private String arsId;
	private String beginTm;
	private String lastTm;
	private String transYn;
	private String fullSectDist;
	private String direction;
	private String stationNo;
	private String trnstnid;
	private String sectSpd;
}
