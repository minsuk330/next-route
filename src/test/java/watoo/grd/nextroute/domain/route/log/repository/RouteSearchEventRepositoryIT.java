package watoo.grd.nextroute.domain.route.log.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.common.config.JpaConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 기록(V79) 계약: 스냅샷 id 중복 무시, 보관 기간 묶음 삭제, 탈퇴 삭제.
 * 표는 엔티티가 아니라 V79 SQL 로 다시 만든다 — ON CONFLICT 가 기대는 부분 유니크 인덱스까지 실제 DDL 로 본다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
class RouteSearchEventRepositoryIT {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired RouteSearchEventRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.execute("drop table if exists route_search_event cascade");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V79__route_search_event.sql")));
        userId = jdbc.queryForObject(
                "insert into users (created_at, updated_at, token_generation) values (now(), now(), 0) returning id",
                Long.class);
    }

    @Test
    @DisplayName("같은 스냅샷 id 는 한 번만, 스냅샷 없는 검색은 여러 번")
    void duplicateSessionIgnored() {
        Instant t = Instant.parse("2026-11-26T06:00:00Z");
        assertThat(repository.insert("sess-1", userId, t, "full", 3, 1200)).isEqualTo(1);
        assertThat(repository.insert("sess-1", userId, t.plusSeconds(1), "full", 3, 900)).isZero();
        assertThat(repository.insert(null, null, t, null, 0, 50)).isEqualTo(1);
        assertThat(repository.insert(null, null, t, null, 0, 60)).isEqualTo(1);

        assertThat(jdbc.queryForObject("select count(*) from route_search_event", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "select latency_ms from route_search_event where search_session_id = 'sess-1'", Integer.class))
                .isEqualTo(1200);
    }

    @Test
    @DisplayName("보관 기간 삭제는 기준 이전만, 묶음 크기만큼")
    void deleteOlderThanInBatches() {
        Instant cutoff = Instant.parse("2026-08-28T00:00:00Z");
        for (int i = 0; i < 3; i++) {
            repository.insert(null, userId, cutoff.minusSeconds(60 + i), null, 0, 10);
        }
        repository.insert(null, userId, cutoff.plusSeconds(60), null, 0, 10);

        assertThat(repository.deleteOlderThan(cutoff, 2)).isEqualTo(2);
        assertThat(repository.deleteOlderThan(cutoff, 2)).isEqualTo(1);
        assertThat(repository.deleteOlderThan(cutoff, 2)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from route_search_event", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("탈퇴 삭제는 그 사용자 행만")
    @Transactional
    void deleteByUser() {
        long other = jdbc.queryForObject(
                "insert into users (created_at, updated_at, token_generation) values (now(), now(), 0) returning id",
                Long.class);
        repository.insert("a", userId, Instant.now(), "full", 1, 10);
        repository.insert("b", other, Instant.now(), "full", 1, 10);

        repository.deleteAllByUserId(userId);

        assertThat(jdbc.queryForObject("select count(*) from route_search_event where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from route_search_event where user_id = ?", Integer.class, other)).isEqualTo(1);
    }
}
