package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * 서울 {@code stationinfo/getStationByUid}(정류장 단위 도착, #577)의 itemList 한 행. 노선 단위
 * {@code getArrInfoByRoute} 와 겹치는 칸은 같은 이름이고, 이 응답에만 있는 칸은 종점 쪽 방향({@code adirection}),
 * 다음 정류장({@code nxtStn}), 혼잡도({@code congestion1/2}, 서울 코드 3 여유 ~ 6 매우혼잡, 0 정보 없음)이다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SeoulStationArrivalItem {
	private String stId;
	private String arsId;
	private String busRouteId;
	private String rtNm;
	private String routeType;
	private String staOrd;
	private String adirection;
	private String nxtStn;

	private String arrmsg1;
	private String traTime1;
	private String vehId1;
	private String sectOrd1;
	private String congestion1;
	private String isLast1;

	private String arrmsg2;
	private String traTime2;
	private String vehId2;
	private String sectOrd2;
	private String congestion2;
	private String isLast2;
}
