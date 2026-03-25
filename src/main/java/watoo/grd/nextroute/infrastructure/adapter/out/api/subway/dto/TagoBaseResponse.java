package watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TagoBaseResponse<T> {

	private Response<T> response;

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Response<T> {
		private Header header;
		private Body<T> body;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Header {
		private String resultCode;
		private String resultMsg;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Body<T> {
		private Items<T> items;
		private int numOfRows;
		private int pageNo;
		private int totalCount;
	}

	@Data
	@JsonIgnoreProperties(ignoreUnknown = true)
	public static class Items<T> {
		private List<T> item;
	}

	public boolean isSuccess() {
		return response != null
				&& response.getHeader() != null
				&& "00".equals(response.getHeader().getResultCode());
	}

	public List<T> getItems() {
		if (response == null || response.getBody() == null
				|| response.getBody().getItems() == null
				|| response.getBody().getItems().getItem() == null) {
			return List.of();
		}
		return response.getBody().getItems().getItem();
	}

	public int getTotalCount() {
		if (response == null || response.getBody() == null) {
			return 0;
		}
		return response.getBody().getTotalCount();
	}
}
