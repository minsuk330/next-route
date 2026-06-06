package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusPositionItem {

	private String vehId;
	private String tmX;
	private String tmY;
	private String sectOrd;
	private String sectDist;
	private String stopFlag;
	private String sectionId;
	private String dataTm;
	private String plainNo;
	private String busType;
	private String lastStnId;
	private String posX;
	private String posY;
	private String routeId;
	@JsonAlias("congestion")
	private String congetion;
}
