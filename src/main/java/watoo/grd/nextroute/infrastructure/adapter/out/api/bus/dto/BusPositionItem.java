package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusPositionItem {

	private String vehId;
	private String stOrd;
	private String tmX;
	private String tmY;
	private String sectSpd;
	private String sectOrd;
	private String stopFlag;
	private String dataTm;
	private String plainNo;
	private String busType;
	private String lastStnId;
	private String isrunyn;
}
