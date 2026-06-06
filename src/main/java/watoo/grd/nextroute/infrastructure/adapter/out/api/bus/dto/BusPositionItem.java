package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusPositionItem {

	private String vehId;
	private String nextStTm;
	private String sectOrd;
	private String sectDist;
	private String rtDist;
	private String stopFlag;
	private String sectionId;
	private String dataTm;
	private String plainNo;
	private String busType;
	private String lastStTm;
	private String lastStnId;
	private String posX;
	private String posY;
	private String isFullFlag;
	private String islastyn;
	private String fullSectDist;
	private String nextStId;
	@JsonAlias("congestion")
	private String congetion;
	private String trnstnid;
	private String gpsX;
	private String gpsY;
	private String isrunyn;
}
