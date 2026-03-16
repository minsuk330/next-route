package watoo.grd.nextroute.infrastructure.adapter.out.api.bus.dto;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement;
import lombok.Data;

import java.util.List;

@Data
@JacksonXmlRootElement(localName = "ServiceResult")
public class BusApiResponse<T> {

	private MsgHeader msgHeader;
	private MsgBody<T> msgBody;

	@Data
	public static class MsgHeader {
		private String headerCd;
		private String headerMsg;
		private int itemCount;
	}

	@Data
	public static class MsgBody<T> {
		@JacksonXmlElementWrapper(useWrapping = false)
		@JacksonXmlProperty(localName = "itemList")
		private List<T> itemList;
	}

	public boolean isSuccess() {
		return msgHeader != null && "0".equals(msgHeader.getHeaderCd());
	}

	public List<T> getItems() {
		if (msgBody == null || msgBody.getItemList() == null) {
			return List.of();
		}
		return msgBody.getItemList();
	}
}
