package watoo.grd.nextroute.infrastructure.adapter.out.persistence.regionalbus;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalBusProvider;
import watoo.grd.nextroute.application.regionalbus.dto.RegionalRouteStop;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore;
import watoo.grd.nextroute.application.regionalbus.port.out.RegionalBusRouteStopStore.RouteKey;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@code regional_bus_route_stop}(V56) 저장소 계약. 실제 마이그레이션 파일을 실행한다 — DDL 복제본을 두면
 * 마이그레이션만 고치고 시험은 통과하는 상태가 생긴다.
 */
@SpringJUnitConfig(JdbcRegionalBusRouteStopStoreIT.Config.class)
class JdbcRegionalBusRouteStopStoreIT {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    static {
        POSTGRES.start();
    }

    private static final Instant AT = Instant.parse("2026-10-01T18:30:00Z");

    @Configuration
    @EnableTransactionManagement
    static class Config {
        @Bean
        DataSource dataSource() throws Exception {
            DriverManagerDataSource ds = new DriverManagerDataSource(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
            try (Connection c = ds.getConnection()) {
                ScriptUtils.executeSqlScript(c, new FileSystemResource(
                        "src/main/resources/db/migration/V56__create_regional_bus_route_stop.sql"));
            }
            return ds;
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcRegionalBusRouteStopStore store(JdbcTemplate jdbcTemplate) {
            return new JdbcRegionalBusRouteStopStore(jdbcTemplate);
        }
    }

    @Autowired
    RegionalBusRouteStopStore store;
    @Autowired
    JdbcTemplate jdbc;

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM regional_bus_route_stop");
    }

    @Test
    @DisplayName("노선 하나의 정류장을 실시간 순번 오름차순으로 돌려준다 — 가상점 표시 포함(#264 PR-4)")
    void findRouteStopsOrdersBySeq() {
        store.replaceRoute(RegionalBusProvider.GG, "233000099", "7777", "31010",
                List.of(stop("C", 3), stop("A", 1), stop("WP-B", 2)), AT);

        List<RegionalRouteStop> stops = store.findRouteStops(RegionalBusProvider.GG, "233000099");

        assertThat(stops).extracting(RegionalRouteStop::stopId).containsExactly("A", "WP-B", "C");
        assertThat(stops.get(1).waypoint()).isTrue();
        assertThat(store.findRouteStops(RegionalBusProvider.TAGO, "233000099")).isEmpty();
    }

    private static RegionalRouteStop stop(String id, int seq) {
        return new RegionalRouteStop(id, id + "명", seq, seq == 2 ? Boolean.TRUE : null, null,
                id.contains("WP") ? Boolean.TRUE : Boolean.FALSE, 37.0, 127.0);
    }

    @Test
    @DisplayName("노선 교체: 옛 행은 사라지고 새 행만 남는다")
    void replaceRoute() {
        store.replaceRoute(RegionalBusProvider.GG, "233000031", "7770", "31010",
                List.of(stop("A", 1), stop("B", 2)), AT);
        store.replaceRoute(RegionalBusProvider.GG, "233000031", "7770", "31010",
                List.of(stop("WP1", 1), stop("C", 2), stop("D", 3)), AT.plusSeconds(60));

        List<String> ids = jdbc.queryForList(
                "SELECT stop_id FROM regional_bus_route_stop WHERE route_id = '233000031' ORDER BY seq", String.class);
        assertThat(ids).containsExactly("WP1", "C", "D");
        assertThat(jdbc.queryForObject(
                "SELECT waypoint FROM regional_bus_route_stop WHERE stop_id = 'WP1'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT turn FROM regional_bus_route_stop WHERE stop_id = 'C'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT turn FROM regional_bus_route_stop WHERE stop_id = 'D'", Boolean.class)).isNull();
    }

    @Test
    @DisplayName("교체 중 실패하면 그 노선의 옛 행이 그대로 남는다(한 트랜잭션)")
    void replaceRouteIsAtomic() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB30300151", "1001", "25", List.of(stop("OLD", 1)), AT);

        // 같은 순번 두 칸 → 유일 제약 위반
        assertThatThrownBy(() -> store.replaceRoute(RegionalBusProvider.TAGO, "DJB30300151", "1001", "25",
                List.of(stop("N1", 1), stop("N2", 1)), AT.plusSeconds(60)))
                .isInstanceOf(RuntimeException.class);

        assertThat(jdbc.queryForList(
                "SELECT stop_id FROM regional_bus_route_stop WHERE route_id = 'DJB30300151'", String.class))
                .containsExactly("OLD");
    }

    @Test
    @DisplayName("TAGO 노선 → 도시코드, 접두사 집합은 TAGO 행만 본다")
    void tagoQueries() {
        store.replaceRoute(RegionalBusProvider.TAGO, "TSB309000001", "1", "32050", List.of(stop("T", 1)), AT);
        store.replaceRoute(RegionalBusProvider.TAGO, "BSB5201002F00", "1002", "21", List.of(stop("B", 1)), AT);
        store.replaceRoute(RegionalBusProvider.GG, "233000031", "7770", "31010", List.of(stop("G", 1)), AT);

        assertThat(store.tagoRouteCityCodes())
                .containsOnly(java.util.Map.entry("TSB309000001", "32050"), java.util.Map.entry("BSB5201002F00", "21"));
        assertThat(store.tagoRoutePrefixes()).containsExactlyInAnyOrder("TSB", "BSB");
        assertThat(store.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("목록에 없는 노선만 지운다")
    void deleteRoutesNotIn() {
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB1", "1", "25", List.of(stop("A", 1), stop("B", 2)), AT);
        store.replaceRoute(RegionalBusProvider.TAGO, "DJB2", "2", "25", List.of(stop("C", 1)), AT);
        store.replaceRoute(RegionalBusProvider.GG, "233000031", "7770", "31010", List.of(stop("G", 1)), AT);

        int deleted = store.deleteRoutesNotIn(Set.of(
                new RouteKey(RegionalBusProvider.TAGO, "DJB1"),
                new RouteKey(RegionalBusProvider.GG, "233000031")));

        assertThat(deleted).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT DISTINCT route_id FROM regional_bus_route_stop ORDER BY 1", String.class))
                .containsExactly("233000031", "DJB1");
    }

    @Test
    @DisplayName("빈 표")
    void empty() {
        assertThat(store.isEmpty()).isTrue();
        assertThat(store.tagoRouteCityCodes()).isEmpty();
    }
}
