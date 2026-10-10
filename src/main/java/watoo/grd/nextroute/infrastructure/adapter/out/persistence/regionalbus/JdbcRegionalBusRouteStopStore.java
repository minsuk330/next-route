package watoo.grd.nextroute.infrastructure.adapter.out.persistence.regionalbus;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code regional_bus_route_stop}(V56). 노선 단위로 통째 교체하므로 엔티티 대신 JdbcTemplate 을 쓴다.
 */
@Repository
@RequiredArgsConstructor
public class JdbcRegionalBusRouteStopStore implements RegionalBusRouteStopStore {

    private static final String INSERT = """
            INSERT INTO regional_bus_route_stop
                (provider, route_id, route_no, city_code, stop_id, stop_name, seq,
                 turn, up_down, waypoint, lat, lon, loaded_at, stop_no)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void replaceRoute(RegionalBusProvider provider, String routeId, String routeNo, String cityCode,
                             List<RegionalRouteStop> stops, Instant loadedAt) {
        jdbcTemplate.update("DELETE FROM regional_bus_route_stop WHERE provider = ? AND route_id = ?",
                provider.name(), routeId);
        Timestamp at = Timestamp.from(loadedAt);
        jdbcTemplate.batchUpdate(INSERT, stops, 500, (ps, stop) -> {
            ps.setString(1, provider.name());
            ps.setString(2, routeId);
            ps.setString(3, routeNo);
            ps.setString(4, cityCode);
            ps.setString(5, stop.stopId());
            ps.setString(6, stop.stopName());
            ps.setInt(7, stop.seq());
            ps.setObject(8, stop.turn(), Types.BOOLEAN);
            ps.setObject(9, stop.upDown(), Types.SMALLINT);
            ps.setObject(10, stop.waypoint(), Types.BOOLEAN);
            ps.setObject(11, stop.lat(), Types.DOUBLE);
            ps.setObject(12, stop.lon(), Types.DOUBLE);
            ps.setTimestamp(13, at);
            ps.setString(14, stop.stopNo());
        });
    }

    @Override
    @Transactional
    public int deleteRoutesNotIn(Set<RouteKey> listed) {
        List<RouteKey> stale = jdbcTemplate.query(
                "SELECT DISTINCT provider, route_id FROM regional_bus_route_stop",
                (rs, i) -> new RouteKey(RegionalBusProvider.valueOf(rs.getString("provider")),
                        rs.getString("route_id"))).stream()
                .filter(key -> !listed.contains(key))
                .toList();
        if (stale.isEmpty()) {
            return 0;
        }
        jdbcTemplate.batchUpdate("DELETE FROM regional_bus_route_stop WHERE provider = ? AND route_id = ?",
                stale, 500, (ps, key) -> {
                    ps.setString(1, key.provider().name());
                    ps.setString(2, key.routeId());
                });
        return stale.size();
    }

    @Override
    public Map<String, String> tagoRouteCityCodes() {
        Map<String, String> out = new HashMap<>();
        jdbcTemplate.query("""
                SELECT DISTINCT route_id, city_code FROM regional_bus_route_stop
                WHERE provider = 'TAGO' AND city_code IS NOT NULL
                """, rs -> {
            out.put(rs.getString("route_id"), rs.getString("city_code"));
        });
        return out;
    }

    @Override
    public Set<String> tagoRoutePrefixes() {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT DISTINCT left(route_id, 3) FROM regional_bus_route_stop WHERE provider = 'TAGO'
                """, String.class));
    }

    @Override
    public Map<String, String> stopNumbers(java.util.Collection<String> stopIds) {
        if (stopIds == null || stopIds.isEmpty()) {
            return Map.of();
        }
        // 같은 정류장이 노선마다 여러 행이다. 번호가 하나로 모이면 그 값, 엇갈리면 모름(null)으로 둔다.
        Map<String, String> out = new HashMap<>();
        Set<String> conflicting = new HashSet<>();
        jdbcTemplate.query(con -> {
            var ps = con.prepareStatement(
                    "SELECT DISTINCT stop_id, btrim(stop_no) AS stop_no FROM regional_bus_route_stop"
                            + " WHERE stop_id = ANY (?) AND stop_no IS NOT NULL AND btrim(stop_no) <> ''");
            ps.setArray(1, con.createArrayOf("varchar", stopIds.toArray()));
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String id = rs.getString("stop_id");
            String no = rs.getString("stop_no");
            String prev = out.putIfAbsent(id, no);
            if (prev != null && !prev.equals(no)) {
                conflicting.add(id);
            }
        });
        conflicting.forEach(out::remove);
        return out;
    }

    @Override
    public boolean hasStopNumbers() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM regional_bus_route_stop WHERE stop_no IS NOT NULL)", Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public boolean isEmpty() {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM regional_bus_route_stop)", Boolean.class);
        return !Boolean.TRUE.equals(exists);
    }

    @Override
    public List<RegionalRouteStop> findRouteStops(RegionalBusProvider provider, String routeId) {
        return jdbcTemplate.query("""
                SELECT stop_id, stop_name, seq, turn, up_down, waypoint, lat, lon
                  FROM regional_bus_route_stop
                 WHERE provider = ? AND route_id = ?
                 ORDER BY seq
                """, (rs, rowNum) -> new RegionalRouteStop(
                        rs.getString("stop_id"), rs.getString("stop_name"), rs.getInt("seq"),
                        (Boolean) rs.getObject("turn"),
                        rs.getObject("up_down") == null ? null : rs.getInt("up_down"),
                        (Boolean) rs.getObject("waypoint"),
                        rs.getObject("lat") == null ? null : rs.getDouble("lat"),
                        rs.getObject("lon") == null ? null : rs.getDouble("lon")),
                provider.name(), routeId);
    }
}
