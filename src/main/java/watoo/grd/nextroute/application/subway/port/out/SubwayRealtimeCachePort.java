package watoo.grd.nextroute.application.subway.port.out;

import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeStatus;

import java.util.Optional;

public interface SubwayRealtimeCachePort {
    void saveSnapshot(SubwayRealtimeSnapshot snapshot, long ttlSeconds);
    Optional<SubwayRealtimeSnapshot> readSnapshot();
    void saveStatus(SubwayRealtimeStatus status);
    Optional<SubwayRealtimeStatus> readStatus();
    void saveBootTime(String bootTimeIso);
}
