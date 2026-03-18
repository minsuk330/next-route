package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SubwayStationMasterResponse {

	@JsonProperty("subwayStationMaster")
	private SubwayStationMasterData data;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class SubwayStationMasterData {
		@JsonProperty("list_total_count")
		private int listTotalCount;

		@JsonProperty("RESULT")
		private Result result;

		@JsonProperty("row")
		private List<SubwayStationMasterItem> rows;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Result {
		@JsonProperty("CODE")
		private String code;
		@JsonProperty("MESSAGE")
		private String message;
	}

	public boolean isSuccess() {
		return data != null && data.getResult() != null
				&& "INFO-000".equals(data.getResult().getCode());
	}

	public List<SubwayStationMasterItem> getItems() {
		if (data == null || data.getRows() == null) {
			return List.of();
		}
		return data.getRows();
	}

	public int getTotalCount() {
		return data != null ? data.getListTotalCount() : 0;
	}
}
