package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** 서울 실시간 위치 API({@code realtimePosition}) 응답 한 행. 필드명은 원문 그대로 둔다. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayPositionItem {

	/** 지하철 호선 ID. 도착정보의 {@code subwayId} 와 같은 공간. */
	private String subwayId;
	private String subwayNm;

	/** 현재 위치 역 ID. */
	private String statnId;
	private String statnNm;

	private String trainNo;

	/** 최종수신날짜. {@link #recptnDt} 가 시각만 담아 오는 응답을 위해 함께 보존한다. */
	private String lastRecptnDt;
	/** 최종수신시간. <b>사건 발생 시각이 아니다.</b> */
	private String recptnDt;

	/** 상하행 구분. 명세상 0=상행/내선, 1=하행/외선. */
	private String updnLine;

	/** 종착역. */
	private String statnTid;
	private String statnTnm;

	/** 0 진입 / 1 도착 / 2 출발 / 3 전역출발. */
	private String trainSttus;

	/** 0 일반 / 1 급행 / 7 특급. */
	private String directAt;

	/** 막차 여부. */
	private String lstcarAt;
}
