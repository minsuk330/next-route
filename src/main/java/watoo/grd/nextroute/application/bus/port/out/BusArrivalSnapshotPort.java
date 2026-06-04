package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusArrivalActiveSnapshot;

import java.util.Map;

public interface BusArrivalSnapshotPort {

	Map<String, BusArrivalActiveSnapshot> findActive(String routeId, String stopId, Integer seq);

	void save(BusArrivalActiveSnapshot snapshot);

	void delete(String routeId, String stopId, Integer seq, String identityKey);
}
