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
		return errorMessage == null ? null : errorMessage.getCode();
	}

	/** API 가 말한 전체 건수. 응답이 완결됐는지 판단하는 근거다. */
	public Integer total() {
		return errorMessage == null ? null : errorMessage.getTotal();
	}

	public List<SubwayPositionItem> getItems() {
		return realtimePositionList == null ? List.of() : realtimePositionList;
	}
}
