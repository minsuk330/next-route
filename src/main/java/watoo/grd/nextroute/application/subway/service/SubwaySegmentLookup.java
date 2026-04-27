package watoo.grd.nextroute.application.subway.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.domain.subway.entity.SubwaySegment;
import watoo.grd.nextroute.domain.subway.service.SubwayDataService;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class SubwaySegmentLookup {

    private final SubwayDataService subwayDataService;

    /** volatile: collector thread reads, scheduler thread replaces */
    private volatile Map<String, Double> map = Map.of();

    @PostConstruct
    public void load() {
        reload();
    }

    /** Daily reload at 03:00 KST */
    @Scheduled(cron = "0 0 3 * * *")
    public void reload() {
        List<SubwaySegment> segments = subwayDataService.findAllSegments();
        Map<String, Double> newMap = new HashMap<>();
        for (SubwaySegment s : segments) {
            if (s.getTravelTime() != null) {
                // departStationId / arriveStationId fields actually store station names
                newMap.put(buildKey(s.getLineId(), s.getDepartStationId(), s.getArriveStationId()),
                        s.getTravelTime());
            }
        }
        this.map = Collections.unmodifiableMap(newMap);
        log.info("[SegmentLookup] Loaded {} segments", newMap.size());
    }

    /** @return travel time in seconds, null if not found */
    public Double get(String lineId, String departName, String arriveName) {
        if (lineId == null || departName == null || arriveName == null) return null;
        return map.get(buildKey(lineId, departName, arriveName));
    }

    private String buildKey(String lineId, String depart, String arrive) {
        return lineId + "|" + depart + "|" + arrive;
    }
}
