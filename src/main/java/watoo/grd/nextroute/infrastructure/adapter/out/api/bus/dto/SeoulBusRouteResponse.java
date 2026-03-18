package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;

import java.util.List;

/**
 * 서울열린데이터광장 busRoute API 응답 DTO.
 * API: GET http://openapi.seoul.go.kr:8088/{key}/xml/busRoute/{start}/{end}/
 */
@Data
@JacksonXmlRootElement(localName = "busRoute")
public class SeoulBusRouteResponse {

	@JacksonXmlProperty(localName = "list_total_count")
	private int listTotalCount;

	@JacksonXmlProperty(localName = "RESULT")
	private Result result;

	@JacksonXmlElementWrapper(useWrapping = false)
	@JacksonXmlProperty(localName = "row")
	private List<Row> rows;

	@Data
	public static class Result {
		@JacksonXmlProperty(localName = "CODE")
		private String code;
		@JacksonXmlProperty(localName = "MESSAGE")
		private String message;
	}

	@Data
	public static class Row {
		@JacksonXmlProperty(localName = "RTE_ID")
		private String routeId;
		@JacksonXmlProperty(localName = "RTE_NM")
		private String routeName;
	}

	public boolean isSuccess() {
		return result != null && "INFO-000".equals(result.getCode());
	}
}
