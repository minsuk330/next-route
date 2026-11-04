package watoo.grd.nextroute.domain.user.repository;

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

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** 중복 무시·탈퇴 삭제 계약(PK = activity_date, user_id). */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
class UserDailyActivityRepositoryIT {

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
        // 다른 경계 IT 와 같이 엔티티에서 스키마 생성(V1 baseline 은 replay 불가). V74 SQL 자체는 노트 ### 검증 참조.
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired UserDailyActivityRepository repository;
    @Autowired JdbcTemplate jdbc;

    private long userId;

    @BeforeEach
    void setUp() {
        jdbc.update("delete from user_daily_activity");
        userId = jdbc.queryForObject(
                "insert into users (created_at, updated_at, token_generation) values (now(), now(), 0) returning id",
                Long.class);
    }

    @Test
    @DisplayName("같은 날 두 번째 INSERT 는 0행이고 첫 값이 남는다")
    void duplicateIgnored() {
        LocalDate day = LocalDate.of(2026, 11, 3);
        assertThat(repository.insertIgnoringDuplicate(day, userId, Instant.parse("2026-11-03T00:00:00Z"), "app/7")).isEqualTo(1);
        assertThat(repository.insertIgnoringDuplicate(day, userId, Instant.parse("2026-11-03T05:00:00Z"), "Dev/6")).isZero();
        assertThat(repository.insertIgnoringDuplicate(day.plusDays(1), userId, Instant.parse("2026-11-04T00:00:00Z"), null)).isEqualTo(1);

        assertThat(jdbc.queryForObject(
                "select app_build from user_daily_activity where activity_date = ? and user_id = ?",
                String.class, day, userId)).isEqualTo("app/7");
    }

    @Test
    @DisplayName("탈퇴 삭제는 그 사용자 행만 지운다")
    @Transactional
    void deleteByUser() {
        long other = jdbc.queryForObject(
                "insert into users (created_at, updated_at, token_generation) values (now(), now(), 0) returning id",
                Long.class);
        LocalDate day = LocalDate.of(2026, 11, 3);
        repository.insertIgnoringDuplicate(day, userId, Instant.now(), null);
        repository.insertIgnoringDuplicate(day, other, Instant.now(), null);

        repository.deleteAllByUserId(userId);

        assertThat(jdbc.queryForObject("select count(*) from user_daily_activity where user_id = ?", Integer.class, userId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from user_daily_activity where user_id = ?", Integer.class, other)).isEqualTo(1);
    }
}
