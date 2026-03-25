package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BusArrivalItem {

	// ===== 공통 =====
	private String stId;           // 정류소 고유 ID
	private String arsId;          // 정류소 번호
	private String stNm;           // 정류소명
	private String busRouteId;     // 노선 ID
	private String busRouteAbrv;   // 노선 약칭
	private String rtNm;           // 노선명
	private String staOrd;         // 요청 정류소 순번
	private String dir;            // 방향
	private String routeType;      // 노선유형
	private String term;           // 배차간격(분)
	private String mkTm;           // 제공시각
	private String deTourAt;       // 우회여부 (00:정상, 11:우회)
	private String nextBus;        // 막차운행여부 (N/Y)
	private String firstTm;        // 첫차시간
	private String lastTm;         // 막차시간

	// ===== 첫 번째 도착예정 버스 =====
	private String arrmsg1;        // 도착정보메시지
	private String vehId1;         // 버스 ID
	private String plainNo1;       // 차량번호
	private String busType1;       // 차량유형 (0:일반, 1:저상, 2:굴절)
	private String sectOrd1;       // 현재구간 순번
	private String stationNm1;     // 현재 정류소명
	private String isArrive1;      // 도착출발여부 (0:운행중, 1:도착)
	private String isLast1;        // 막차여부 (0:아님, 1:막차)
	private String full1;          // 만차여부

	// 예측 시간
	private String exps1;          // 지수평활 도착예정시간(초)
	private String kals1;          // 기타1 도착예정시간(초)
	private String neus1;          // 기타2 도착예정시간(초)
	private String goal1;          // 종점 도착예정시간(초)

	// 보정 계수
	private String avgCf1;         // 이동평균 보정계수
	private String expCf1;         // 지수평활 보정계수
	private String kalCf1;         // 기타1평균 보정계수
	private String neuCf1;         // 기타2평균 보정계수

	// 여행 정보
	private String traTime1;       // 여행시간(분)
	private String traSpd1;        // 여행속도(km/h)

	// 혼잡도 (뒷차)
	@JsonProperty("brdrde_Num1")
	private String brdrdeNum1;     // 혼잡도 or 재차인원
	@JsonProperty("brerde_Div1")
	private String brerdDiv1;      // 혼잡도 구분 (0:없음, 2:재차인원, 4:혼잡도)

	// 혼잡도 (재차)
	@JsonProperty("reride_Num1")
	private String rerideNum1;     // 혼잡도 or 재차인원
	@JsonProperty("rerdie_Div1")
	private String rerdieDiv1;     // 혼잡도 구분

	// 다음 정류소
	private String nstnId1;        // 다음정류소 ID
	private String nstnOrd1;       // 다음정류소 순번
	private String nstnSec1;       // 다음정류소 예정여행시간
	private String nstnSpd1;       // 다음정류소 예정여행속도

	// 1번째 주요정류소
	private String nmainOrd1;      // 순번
	private String nmainSec1;      // 예정여행시간
	private String nmainStnid1;    // ID

	// 2번째 주요정류소
	private String nmain2Ord1;     // 순번
	private String namin2Sec1;     // 예정여행시간
	private String nmain2Stnid1;   // ID

	// 3번째 주요정류소
	private String nmain3Ord1;     // 순번
	private String nmain3Sec1;     // 예정여행시간
	private String nmain3Stnid1;   // ID

	// ===== 두 번째 도착예정 버스 =====
	private String arrmsg2;
	private String vehId2;
	private String plainNo2;
	private String busType2;
	private String sectOrd2;
	private String stationNm2;
	private String isArrive2;
	private String isLast2;
	private String full2;

	private String exps2;
	private String kals2;
	private String neus2;
	private String goal2;

	private String avgCf2;
	private String expCf2;
	private String kalCf2;
	private String neuCf2;

	private String traTime2;
	private String traSpd2;

	@JsonProperty("brdrde_Num2")
	private String brdrdeNum2;
	@JsonProperty("brerde_Div2")
	private String brerdDiv2;

	@JsonProperty("reride_Num2")
	private String rerideNum2;
	@JsonProperty("rerdie_Div2")
	private String rerdieDiv2;

	private String nstnId2;
	private String nstnOrd2;
	private String nstnSec2;
	private String nstnSpd2;

	private String nmainOrd2;
	private String nmainSec2;
	private String nmainStnid2;

	private String nmain2Ord2;
	private String namin2Sec2;
	private String nmain2Stnid2;

	private String nmain3Ord2;
	private String nmain3Sec2;
	private String nmain3Stnid2;
}
