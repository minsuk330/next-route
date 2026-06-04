package watoo.grd.nextroute.application.bus.dto;

public record BusRidershipInfo(
		String routeNo,
		String routeName,
		long getOnTotal,
		long getOffTotal
) {
	public long totalUsage() {
		return getOnTotal + getOffTotal;
	}
}
