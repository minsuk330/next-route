package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusRoutePathItem {

	private String no;
	private String gpsX;
	private String gpsY;
}
