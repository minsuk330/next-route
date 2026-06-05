package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.ArrivalScope;
import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;

import java.util.Map;
import java.util.Set;

public interface BusArrivalSnapshotPort {

	Map<String, BusArrivalActiveSnapshot> findActive(String routeId, String stopId, Integer seq);

	/** 해당 노선에서 현재 active snapshot이 남아 있는 모든 scope. API 응답에서 사라진 scope도 reconcile 대상에 포함시키기 위함. */
	Set<ArrivalScope> findActiveScopes(String routeId);

	void save(BusArrivalActiveSnapshot snapshot);

	void delete(String routeId, String stopId, Integer seq, String identityKey);

	/** scope의 snapshot hash가 비어 있으면 active scope index에서도 제거한다(고아 멤버 self-heal). */
	void cleanupScopeIfEmpty(ArrivalScope scope);
}
