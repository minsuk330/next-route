package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusArrivalItem {

	private String stId;
	private String busRouteId;
	private String staOrd;
	private String arrmsg1;
	private String arrmsg2;
	private String vehId1;
	private String vehId2;
	private String plainNo1;
	private String plainNo2;
	private String sectOrd1;
	private String sectOrd2;
	private String stationNm1;
	private String stationNm2;
	private String traTime1;
	private String traTime2;
	private String traSpd1;
	private String traSpd2;
	private String isArrive1;
	private String isArrive2;
	private String exps1;
	private String exps2;
}
