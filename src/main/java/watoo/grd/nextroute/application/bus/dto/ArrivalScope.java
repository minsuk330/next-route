package watoo.grd.nextroute.application.bus.dto;

/**
 * 도착 스냅샷의 추적 단위. route-stop-seq 조합으로 한 정류장에서의 노선/방향 도착열을 식별한다.
 */
public record ArrivalScope(String routeId, String stopId, Integer seq) {

	public static ArrivalScope from(BusArrivalInfo info) {
		if (!hasText(info.routeId()) || !hasText(info.stopId()) || info.seq() == null) {
			return null;
		}
		return new ArrivalScope(normalize(info.routeId()), normalize(info.stopId()), info.seq());
	}

	private static boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

	private static String normalize(String value) {
		return value == null ? null : value.trim();
	}
}
