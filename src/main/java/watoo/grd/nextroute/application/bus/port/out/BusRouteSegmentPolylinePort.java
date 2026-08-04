package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineRange;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;

import java.util.List;
import java.util.Optional;

public interface BusRouteSegmentPolylinePort {

	void replaceRouteSegments(String routeId, List<BusRouteSegmentPolylineData> segments);

	Optional<BusRouteSegmentPolylineRange> findRange(String routeId, int fromSeq, int toSeq);

	/**
	 * 구간을 합치지 않고 원본 단위로 읽는다. 여정 진행 판정용이다.
	 *
	 * <p>{@link #findRange} 와 달리 불량 구간에서 좌표를 비우지 않는다. 어느 구간이 불량한지
	 * 그대로 알려 줘야 앱이 그 구간만 근거에서 빼고 다음 정상 구간에서 다시 맞출 수 있다.
	 *
	 * @return 정류장 순번 순으로 정렬된 구간. 노선·범위에 데이터가 없으면 빈 목록
	 */
	List<BusRouteSegmentPolylineSection> findSections(String routeId, int fromSeq, int toSeq);
}
