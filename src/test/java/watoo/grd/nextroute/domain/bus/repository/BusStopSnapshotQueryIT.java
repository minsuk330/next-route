package watoo.grd.nextroute.domain.bus.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실 Postgres 로 스냅샷 쿼리의 계약을 검증한다:
 * (a) 소프트삭제/좌표 null 제외, (b) stop_id 오름차순 고정,
 * (c) 버전 프로브의 count/epoch 매핑이 실제로 되는지(SQL 에서 epoch 변환하는 이유).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class BusStopSnapshotQueryIT {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"))
                    .withInitScript("db/it/bus-stop-snapshot-it-schema.sql");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        r.add("spring.flyway.enabled", () -> "false");
    }

    @Autowired BusStopRepository busStopRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        jdbcTemplate.execute("TRUNCATE TABLE bus_stop");
    }

    @Test
    void snapshot_excludesSoftDeletedAndNullCoordinates_andSortsByStopId() {
        insert("300", "03003", "정상C", 37.53, 127.03, "2026-08-10 10:00:00", null);
        insert("100", "01001", "정상A", 37.51, 127.01, "2026-08-11 10:00:00", null);
        insert("200", "02002", "삭제됨", 37.52, 127.02, "2026-08-12 10:00:00", "2026-08-13 10:00:00");
        insert("400", "04004", "좌표없음", null, 127.04, "2026-08-14 10:00:00", null);

        List<BusStopSnapshotProjection> rows = busStopRepository.findAllForSnapshot();

        assertThat(rows).extracting(BusStopSnapshotProjection::getStopId)
                .containsExactly("100", "300");
        assertThat(rows.get(0).getStopName()).isEqualTo("정상A");
        assertThat(rows.get(0).getArsId()).isEqualTo("01001");
        assertThat(rows.get(0).getLatitude()).isEqualTo(37.51);
        assertThat(rows.get(0).getLongitude()).isEqualTo(127.01);
    }

    /** 프로브의 WHERE 절이 본문 쿼리와 같아야 한다 — 다르면 버전은 그대로인데 본문만 바뀐다. */
    @Test
    void versionProbe_countsOnlySnapshotRows_andMapsMaxUpdatedEpoch() {
        insert("100", "01001", "정상A", 37.51, 127.01, "2026-08-11 10:00:00", null);
        insert("300", "03003", "정상C", 37.53, 127.03, "2026-08-15 11:40:11", null);
        insert("200", "02002", "삭제됨", 37.52, 127.02, "2026-08-18 10:00:00", "2026-08-18 11:00:00");
        insert("400", "04004", "좌표없음", null, 127.04, "2026-08-19 10:00:00", null);

        BusStopSnapshotVersionProjection probed = busStopRepository.probeSnapshotVersion();

        assertThat(probed.getStopCount()).isEqualTo(2);
        long expectedEpoch = jdbcTemplate.queryForObject(
                "SELECT extract(epoch FROM timestamp '2026-08-15 11:40:11')::bigint", Long.class);
        assertThat(probed.getMaxUpdatedEpochSec()).isEqualTo(expectedEpoch);
    }

    @Test
    void versionProbe_emptyTable_yieldsZeroCountAndZeroEpoch() {
        BusStopSnapshotVersionProjection probed = busStopRepository.probeSnapshotVersion();

        assertThat(probed.getStopCount()).isZero();
        assertThat(probed.getMaxUpdatedEpochSec()).isZero();
    }

    private void insert(String stopId, String arsId, String stopName,
                        Double lat, Double lng, String updatedAt, String deletedAt) {
        jdbcTemplate.update("""
                        INSERT INTO bus_stop (stop_id, ars_id, stop_name, latitude, longitude,
                                              created_at, updated_at, deleted_at)
                        VALUES (?, ?, ?, ?, ?, ?::timestamp, ?::timestamp, ?::timestamp)
                        """,
                stopId, arsId, stopName, lat, lng, updatedAt, updatedAt, deletedAt);
    }
}
