package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayArrivalItem {

	private String subwayId;
	private String updnLine;
	private String statnFid;
	private String statnTid;
	private String statnId;
	private String statnNm;
	private String trnsitCo;
	private String ordkey;
	private String subwayList;
	private String statnList;
	private String btrainSttus;
	private String btrainNo;
	private String bstatnId;
	private String bstatnNm;
	private String barvlDt;
	private String arvlMsg2;
	private String arvlMsg3;
	private String arvlCd;
	private String recptnDt;
	private String trainLineNm;
	private String lstcarAt;
}
