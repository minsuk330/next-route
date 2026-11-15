package watoo.grd.nextroute.infrastructure.adapter.out.persistence.regionalsubway;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSubwayTimetableStore;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * {@code regional_subway_station}, {@code regional_subway_departure}(V76). 역 단위 통째 교체라 엔티티 대신 JdbcTemplate.
 */
@Repository
@RequiredArgsConstructor
public class JdbcRegionalSubwayTimetableStore implements RegionalSubwayTimetableStore {

    private static final String UPSERT_STATION = """
            INSERT INTO regional_subway_station
                (station_key, line_code, line_name, station_name, seq, tago_station_id, provider_station_code,
                 gtfs_stop_id, latitude, longitude, geom, loaded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
            ON CONFLICT (station_key) DO UPDATE SET
                line_code = EXCLUDED.line_code, line_name = EXCLUDED.line_name, station_name = EXCLUDED.station_name,
                seq = EXCLUDED.seq, tago_station_id = EXCLUDED.tago_station_id,
                provider_station_code = EXCLUDED.provider_station_code, gtfs_stop_id = EXCLUDED.gtfs_stop_id,
                latitude = EXCLUDED.latitude, longitude = EXCLUDED.longitude, geom = EXCLUDED.geom,
                loaded_at = EXCLUDED.loaded_at
            """;

    private static final String INSERT_DEPARTURE = """
            INSERT INTO regional_subway_departure
                (station_key, day_type, dep_seconds, terminal_name, terminal_seq, source, source_date, loaded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void upsertStations(List<RegionalStationSeed> stations, Instant loadedAt) {
        Timestamp at = Timestamp.from(loadedAt);
        // UNIQUE(line_code, seq) 가 순서 바뀐 시드에서 중간에 충돌하지 않게, 이번에 넣는 노선의 seq 를 먼저 음수로 비켜 둔다
        String[] lines = stations.stream().map(RegionalStationSeed::lineCode).distinct().toArray(String[]::new);
        String[] keys = stations.stream().map(RegionalStationSeed::stationKey).toArray(String[]::new);
        // 시드에서 빠진 역은 지운다(출발 행 포함). 남겨 두면 음수 seq 로 비켜 둔 값과 다음 실행에서 충돌한다
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement("""
                    DELETE FROM regional_subway_departure WHERE station_key IN (
                        SELECT station_key FROM regional_subway_station
                         WHERE line_code = ANY (?) AND station_key <> ALL (?))""");
            ps.setArray(1, con.createArrayOf("varchar", lines));
            ps.setArray(2, con.createArrayOf("varchar", keys));
            return ps;
        });
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "DELETE FROM regional_subway_station WHERE line_code = ANY (?) AND station_key <> ALL (?)");
            ps.setArray(1, con.createArrayOf("varchar", lines));
            ps.setArray(2, con.createArrayOf("varchar", keys));
            return ps;
        });
        jdbcTemplate.update(con -> {
            var ps = con.prepareStatement(
                    "UPDATE regional_subway_station SET seq = -1 - seq WHERE seq >= 0 AND line_code = ANY (?)");
            ps.setArray(1, con.createArrayOf("varchar", lines));
            return ps;
        });
        jdbcTemplate.batchUpdate(UPSERT_STATION, stations, 500, (ps, s) -> {
            ps.setString(1, s.stationKey());
            ps.setString(2, s.lineCode());
            ps.setString(3, s.lineName());
            ps.setString(4, s.stationName());
            ps.setInt(5, s.seq());
            ps.setString(6, s.tagoStationId());
            ps.setString(7, s.providerStationCode());
            ps.setString(8, s.gtfsStopId());
            ps.setDouble(9, s.lat());
            ps.setDouble(10, s.lon());
            ps.setDouble(11, s.lon());
            ps.setDouble(12, s.lat());
            ps.setTimestamp(13, at);
        });
    }

    @Override
    @Transactional
    public void replaceDepartures(String stationKey, List<StoredDeparture> departures, String source,
                                  LocalDate sourceDate, Instant loadedAt) {
        jdbcTemplate.update("DELETE FROM regional_subway_departure WHERE station_key = ?", stationKey);
        Timestamp at = Timestamp.from(loadedAt);
        Date date = sourceDate == null ? null : Date.valueOf(sourceDate);
        jdbcTemplate.batchUpdate(INSERT_DEPARTURE, departures, 1000, (ps, d) -> {
            ps.setString(1, stationKey);
            ps.setString(2, d.dayType());
            ps.setInt(3, d.depSeconds());
            ps.setString(4, d.terminalName());
            ps.setInt(5, d.terminalSeq());
            ps.setString(6, source);
            ps.setDate(7, date);
            ps.setTimestamp(8, at);
        });
    }

    @Override
    public boolean isEmpty() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM regional_subway_departure)", Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }
}
