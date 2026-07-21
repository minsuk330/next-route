package watoo.grd.nextroute.infrastructure.adapter.out.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.route.dto.SearchHistoryEntry;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실 Postgres(Testcontainers)로 검색 이력 저장의 핵심 계약을 검증한다:
 * (a) 신규 insert, (b) 동일좌표 재검색 → 1행·searched_at·이름 갱신(collapse),
 * (c) cap 초과 → trim 으로 N 유지, (d) 동시 동일좌표 → unique 충돌 없이 1행(ON CONFLICT).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(SearchHistoryPersistenceAdapter.class)
class SearchHistoryPersistenceAdapterIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/search-history-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired SearchHistoryPersistenceAdapter adapter;
    @Autowired JdbcTemplate jdbcTemplate;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("delete from route_search_history");
        jdbcTemplate.update("delete from users");
        userId = jdbcTemplate.queryForObject(
                "insert into users (created_at) values (now()) returning id", Long.class);
    }

    private SearchHistoryEntry entry(double sx, double sy, double ex, double ey,
                                     String sName, String eName, int pathCount, LocalDateTime at) {
        return new SearchHistoryEntry(userId, sx, sy, ex, ey, sName, eName, pathCount, 2, at);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from route_search_history where user_id = ?", Integer.class, userId);
    }

    @Test
    void TC_신규_좌표는_새_행으로_저장된다() {
        adapter.upsertAndTrim(entry(127.0, 37.5, 127.01, 37.51, "A", "B", 3,
                LocalDateTime.parse("2026-07-21T10:00:00")), 50);

        assertThat(rowCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from route_search_history where user_id = ?", userId);
        assertThat(row.get("start_name")).isEqualTo("A");
        assertThat(row.get("path_count")).isEqualTo(3);
    }

    @Test
    void TC_동일좌표_재검색은_1행으로_collapse되고_시각과_이름이_갱신된다() {
        adapter.upsertAndTrim(entry(127.0, 37.5, 127.01, 37.51, "옛출발", "옛도착", 2,
                LocalDateTime.parse("2026-07-21T10:00:00")), 50);
        adapter.upsertAndTrim(entry(127.0, 37.5, 127.01, 37.51, "새출발", "새도착", 5,
                LocalDateTime.parse("2026-07-21T11:00:00")), 50);

        assertThat(rowCount()).isEqualTo(1);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from route_search_history where user_id = ?", userId);
        assertThat(row.get("start_name")).isEqualTo("새출발");
        assertThat(row.get("end_name")).isEqualTo("새도착");
        assertThat(row.get("path_count")).isEqualTo(5);
        assertThat(((java.sql.Timestamp) row.get("searched_at")).toLocalDateTime())
                .isEqualTo(LocalDateTime.parse("2026-07-21T11:00:00"));
    }

    @Test
    void TC_cap_초과분은_trim으로_최근_N만_남는다() {
        LocalDateTime base = LocalDateTime.parse("2026-07-21T10:00:00");
        for (int i = 0; i < 5; i++) {
            adapter.upsertAndTrim(
                    entry(127.0 + i, 37.5, 127.01, 37.51, "s" + i, "e" + i, 1, base.plusMinutes(i)),
                    3);
        }

        assertThat(rowCount()).isEqualTo(3);
        // 최근 3개(searched_at desc)만 남는다 → s4,s3,s2.
        List<String> names = jdbcTemplate.queryForList(
                "select start_name from route_search_history where user_id = ? order by searched_at desc",
                String.class, userId);
        assertThat(names).containsExactly("s4", "s3", "s2");
    }

    @Test
    void TC_동시_동일좌표_insert도_충돌없이_1행() throws Exception {
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                final int n = i;
                futures.add(pool.submit(() -> adapter.upsertAndTrim(
                        entry(127.0, 37.5, 127.01, 37.51, "s" + n, "e" + n, n,
                                LocalDateTime.parse("2026-07-21T10:00:00").plusSeconds(n)),
                        50)));
            }
            for (Future<?> f : futures) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(rowCount()).isEqualTo(1);
    }
}
