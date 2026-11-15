package watoo.grd.nextroute.application.regionalsubway.port.out;

import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code regional_subway_station}, {@code regional_subway_departure}(V76) 저장소.
 */
public interface RegionalSubwayTimetableStore {

    /** 시드 역을 station_key 기준으로 upsert 한다. */
    void upsertStations(List<RegionalStationSeed> stations, Instant loadedAt);

    /** 그 역의 출발 행을 통째로 바꾼다(삭제 + 삽입 한 트랜잭션). */
    void replaceDepartures(String stationKey, List<StoredDeparture> departures, String source,
                           LocalDate sourceDate, Instant loadedAt);

    boolean isEmpty();

    /** 저장할 출발 행. */
    record StoredDeparture(String dayType, int depSeconds, String terminalName, int terminalSeq) {
    }
}
