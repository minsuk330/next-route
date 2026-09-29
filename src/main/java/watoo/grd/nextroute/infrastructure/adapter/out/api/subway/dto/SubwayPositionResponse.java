package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 실시간 위치 API 응답 봉투.
 *
 * <p>도착정보({@link SubwayApiResponse})와 봉투 모양이 같고 목록 키만 다르다. 그래도 클래스를
 * 나누는 이유는 담긴 것의 뜻이 다르기 때문이다 — 한 타입으로 합치면 "역별 도착 예정"과
 * "열차 현재 위치"를 같은 것으로 다루는 코드가 언젠가 생긴다.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayPositionResponse {

	private ErrorMessage errorMessage;

	/**
	 * {@code errorMessage} 없이 <b>최상위</b>에 오는 결과 코드. "데이터 없음"({@code INFO-200})과
	 * 한도 초과가 이 모양이다 — {@code {"status":500,"code":"INFO-200","message":"해당하는 데이터가 없습니다."}}.
	 * 이것을 안 읽으면 둘 다 {@code code=null} 인 정체불명의 오류가 된다.
	 */
	private String code;
	private String message;

	@JsonProperty("realtimePositionList")
	private List<SubwayPositionItem> realtimePositionList;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class ErrorMessage {
		private int status;
		private String code;
		private String message;
		private int total;
	}

	public boolean isSuccess() {
		return errorMessage != null && "INFO-000".equals(errorMessage.getCode());
	}

	public String code() {
		return errorMessage == null ? code : errorMessage.getCode();
	}

	public String message() {
		return errorMessage == null ? message : errorMessage.getMessage();
	}

	/** 그 노선에 지금 다니는 열차가 없다. 막차 뒤·첫차 전에 매일 온다 — 오류가 아니다. */
	public boolean isNoData() {
		return "INFO-200".equals(code());
	}

	/** API 가 말한 전체 건수. 응답이 완결됐는지 판단하는 근거다. */
	public Integer total() {
		return errorMessage == null ? null : errorMessage.getTotal();
	}

	public List<SubwayPositionItem> getItems() {
		return realtimePositionList == null ? List.of() : realtimePositionList;
	}
}
