package watoo.grd.nextroute.application.bus.dto;

import java.util.List;

public record BusRidershipFetchResult(
		String month,
		int totalRowCount,
		List<BusRidershipInfo> rows
) {
	public int fetchedRowCount() {
		return rows.size();
	}
}
