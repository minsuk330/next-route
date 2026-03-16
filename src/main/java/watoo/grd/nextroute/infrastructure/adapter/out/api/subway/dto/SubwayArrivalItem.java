package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayArrivalItem {

	private String subwayId;
	private String updnLine;
	private String statnId;
	private String statnNm;
	private String btrainNo;
	private String bstatnNm;
	private String barvlDt;
	private String arvlMsg2;
	private String arvlMsg3;
	private String arvlCd;
	private String recptnDt;
	private String trainLineNm;
}
