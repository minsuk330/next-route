package watoo.grd.nextroute.domain.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.common.config.JpaConfig;
import watoo.grd.nextroute.infrastructure.adapter.out.persistence.mapping.TransitIdentityMappingRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V49 를 실 PostgreSQL 로 실행해 스키마 계약을 고정한다(마이그레이션 파일 그대로 @Sql).
 * 여기서 잡는 것: (1) trip_pattern=null 중복이 NULLS NOT DISTINCT 로 막힌다, (2) 논리삭제 행은
 * findMappings 에 안 나온다. adapter mock 테스트로는 못 잡는 실 DB 동작이다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaConfig.class)
@Sql(scripts = "classpath:db/migration/V49__create_transit_identity_mapping.sql",
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class TransitIdentityMappingSchemaIT {

    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    static { postgres.start(); }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired TransitIdentityMappingRepository repository;

    private void insert(String provider, String deletedAt) {
        jdbc.update("""
                insert into transit_identity_mapping
                (created_at, updated_at, deleted_at, data_version, feed_id, agency_id,
                 source_route_id, trip_pattern, source_stop_id, occurrence_index,
                 provider, provider_route_id, provider_stop_id, verification_status)
                values (now(), now(), ?::timestamp, 'd1','1','1:A1','1:R', NULL, '1:S1', 0,
                        ?, 'SR', 'SS', 'VERIFIED')
                """, deletedAt, provider);
    }

    @Test
    @DisplayName("trip_pattern=null 이라도 같은 (canonical+provider) 중복은 막힌다 (NULLS NOT DISTINCT)")
    void nullTripPatternUniqueEnforced() {
        assertThatCode(() -> insert("SEOUL_BUS", null)).doesNotThrowAnyException();
        assertThatThrownBy(() -> insert("SEOUL_BUS", null))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 다른 provider 는 허용
        assertThatCode(() -> insert("SEOUL_SUBWAY", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("논리삭제된 행은 findMappings 에서 제외된다")
    void softDeletedExcluded() {
        insert("SEOUL_BUS_LIVE", null);
        insert("SEOUL_BUS_DELETED", "2026-09-08 00:00:00");
        var rows = repository.findMappings("d1", "1", "1:A1", "1:R", null, "1:S1", 0);
        assertThat(rows).extracting("provider").containsExactly("SEOUL_BUS_LIVE");
    }
}
