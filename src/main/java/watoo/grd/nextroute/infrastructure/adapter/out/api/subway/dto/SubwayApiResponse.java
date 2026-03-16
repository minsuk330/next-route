package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayApiResponse {

	private ErrorMessage errorMessage;

	@JsonProperty("realtimeArrivalList")
	private List<SubwayArrivalItem> realtimeArrivalList;

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

	public List<SubwayArrivalItem> getItems() {
		if (realtimeArrivalList == null) {
			return List.of();
		}
		return realtimeArrivalList;
	}
}
