package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SubwayStationIdLookup {

    /**
     * key: Seoul realtime API statnId (e.g. "1002000233")
     * value: station name (e.g. "강남")
     * Accumulated from every ALL response. May be incomplete in early cycles — allowed.
     */
    private final ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

    /** Called by collector each cycle. Accumulates (statnId → statnNm) pairs. */
    public void update(List<SubwayArrivalInfo> arrivals) {
        for (SubwayArrivalInfo a : arrivals) {
            if (a.stationId() != null && a.stationName() != null) {
                map.putIfAbsent(a.stationId(), a.stationName());
            }
        }
    }

    /** @return station name, null if stationId not yet seen */
    public String getStationName(String stationId) {
        if (stationId == null) return null;
        return map.get(stationId);
    }

    public int size() {
        return map.size();
    }
}
