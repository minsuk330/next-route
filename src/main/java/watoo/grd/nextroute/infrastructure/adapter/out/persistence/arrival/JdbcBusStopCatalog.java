package watoo.grd.nextroute.infrastructure.adapter.out.persistence.arrival;

import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.port.out.BusStopCatalogPort;

/**
 * 정류장 단위 도착(#577)이 부를 공급자를 정하는 카탈로그 조회. 모두 인덱스를 탄다: {@code bus_stop.stop_id}(unique),
 * {@code regional_bus_route_stop (provider, stop_id)}(V56).
 */
@Component
@RequiredArgsConstructor
public class JdbcBusStopCatalog implements BusStopCatalogPort {

    private final JdbcTemplate jdbcTemplate;

    /** 지워진 정류장과 번호가 비었거나 0 인 정류장(가상 정류장)은 서울 조회를 할 수 없다. */
    @Override
    public Optional<String> seoulArsId(String stopId) {
        List<String> ars = jdbcTemplate.queryForList(
                "SELECT ars_id FROM bus_stop WHERE stop_id = ? AND deleted_at IS NULL", String.class, stopId);
        return ars.stream()
                .filter(a -> a != null && !a.isBlank() && !"0".equals(a.trim()))
                .map(String::trim)
                .findFirst();
    }

    /** 통과만 하는 점(waypoint)은 타는 정류장이 아니다. */
    @Override
    public boolean isGyeonggiStop(String stopId) {
        Boolean exists = jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM regional_bus_route_stop"
                        + " WHERE provider = 'GG' AND stop_id = ? AND waypoint IS NOT TRUE)",
                Boolean.class, stopId);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    public List<String> tagoCityCodes(String stopId) {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT city_code FROM regional_bus_route_stop"
                        + " WHERE provider = 'TAGO' AND stop_id = ? AND city_code IS NOT NULL ORDER BY city_code",
                String.class, stopId);
    }
}
