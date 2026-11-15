package watoo.grd.nextroute.infrastructure.adapter.out.persistence.regionalsubway;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSubwayTimetableStore.StoredDeparture;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V76 를 실제 PostGIS 에 적용하고 저장소 계약(역 upsert, 역 단위 교체, 중복 키)을 확인한다. */
class JdbcRegionalSubwayTimetableStoreIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/migration/V76__create_regional_subway_timetable.sql"),
                    "/docker-entrypoint-initdb.d/zz_v76.sql");

    private static JdbcTemplate jdbc;
    private static JdbcRegionalSubwayTimetableStore store;

    @BeforeAll
    static void start() {
        PG.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        store = new JdbcRegionalSubwayTimetableStore(jdbc);
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    private static RegionalStationSeed seed(String key, String name, int seq) {
        return new RegionalStationSeed(key, "BS1", "부산1호선", name, seq, "MTRBS1" + key.substring(4), key.substring(4),
                "1:RS_ACC1_S-2-0" + key.substring(4), 35.15 + seq * 0.01, 129.05);
    }

    @Test
    @DisplayName("역 upsert 는 seq 가 바뀌어도 UNIQUE(line_code, seq) 에 안 걸리고 geom 을 채운다. 출발 행은 역 단위로 통째 교체")
    void upsertAndReplace() {
        Instant t = Instant.parse("2026-11-16T00:00:00Z");
        store.upsertStations(List.of(seed("BS1-119", "서면", 0), seed("BS1-120", "부전", 1)), t);
        store.upsertStations(List.of(seed("BS1-119", "서면", 1), seed("BS1-120", "부전", 0)), t);   // 순서 뒤바뀜
        assertThat(jdbc.queryForObject("SELECT seq FROM regional_subway_station WHERE station_key='BS1-119'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regional_subway_station WHERE geom IS NOT NULL", Integer.class)).isEqualTo(2);

        assertThat(store.isEmpty()).isTrue();
        store.replaceDepartures("BS1-119", List.of(new StoredDeparture("WD", 20235, "다대포해수욕장", 39),
                new StoredDeparture("SA", 21600, "노포", 0)), "BUSAN_API", null, t);
        store.replaceDepartures("BS1-119", List.of(new StoredDeparture("HO", 86400, "다대포해수욕장", 39)),
                "BUSAN_API", LocalDate.of(2026, 7, 20), t);
        assertThat(jdbc.queryForList("SELECT day_type FROM regional_subway_departure WHERE station_key='BS1-119'", String.class))
                .containsExactly("HO");
        assertThat(store.isEmpty()).isFalse();
    }

    @Test
    @DisplayName("시드에서 빠진 역은 출발 행과 함께 지워진다 — 음수로 비켜 둔 seq 가 다음 실행과 충돌하지 않는다")
    void removedStationIsDeleted() {
        Instant t = Instant.parse("2026-11-16T00:00:00Z");
        List<RegionalStationSeed> seven = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) seven.add(seed("BS1-2" + (10 + i), "역" + i, i));
        store.upsertStations(seven, t);
        store.replaceDepartures("BS1-216", List.of(new StoredDeparture("WD", 20000, "역0", 0)), "BUSAN_API", null, t);

        List<RegionalStationSeed> six = new java.util.ArrayList<>(seven.subList(0, 6));   // BS1-216(seq 6) 제거
        store.upsertStations(six, t);
        store.upsertStations(six, t);   // 예전 구현은 여기서 UNIQUE(line_code, seq) 충돌

        assertThat(jdbc.queryForObject("SELECT count(*) FROM regional_subway_station WHERE station_key='BS1-216'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM regional_subway_departure WHERE station_key='BS1-216'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT min(seq) FROM regional_subway_station WHERE station_key LIKE 'BS1-21%'", Integer.class)).isZero();
    }

    @Test
    @DisplayName("CHECK: 범위 밖 노선 코드, 모르는 요일은 거부")
    void checks() {
        Instant t = Instant.parse("2026-11-16T00:00:00Z");
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO regional_subway_station (station_key, line_code, line_name, station_name, seq, latitude, longitude, geom, loaded_at)
                VALUES ('DHL-1','DHL','동해선','부전',0,35.1,129.0, ST_SetSRID(ST_MakePoint(129.0,35.1),4326)::geography, now())"""))
                .hasMessageContaining("regional_subway_station_line_check");
        assertThatThrownBy(() -> store.replaceDepartures("BS1-120",
                List.of(new StoredDeparture("XX", 1, "노포", 0)), "BUSAN_API", null, t))
                .hasMessageContaining("regional_subway_departure_day_check");
    }
}
