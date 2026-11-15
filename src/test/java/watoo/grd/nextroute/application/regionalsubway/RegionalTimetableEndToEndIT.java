package watoo.grd.nextroute.application.regionalsubway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalSubwayLoadResult;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalDepartureQueryService;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalSubwayTimetableLoadService;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;
import watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway.DaeguExportFileSource;
import watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway.GwangjuCsvSource;
import watoo.grd.nextroute.infrastructure.adapter.out.persistence.regionalsubway.JdbcRegionalSubwayTimetableStore;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 커밋된 공식 파일(대구 내보내기 2026-11-16, 광주 CSV 2026-11-01)을 시드 → 적재 → V76 → 조회까지 그대로 돌려
 * 같은 날 받은 공식 응답과 맞는지 본다. 원격 원천(부산 API, 대전 TAGO)은 없다.
 */
class RegionalTimetableEndToEndIT {

    @SuppressWarnings("resource")
    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(
            DockerImageName.parse("postgis/postgis:17-3.5").asCompatibleSubstituteFor("postgres"))
            .withCopyFileToContainer(MountableFile.forClasspathResource("db/migration/V76__create_regional_subway_timetable.sql"),
                    "/docker-entrypoint-initdb.d/zz_v76.sql");

    private static RegionalDepartureQueryService query;
    private static RegionalSubwayLoadResult loaded;

    private static final Map<LocalDate, String> HOLIDAYS = Map.of(LocalDate.of(2026, 9, 25), "추석");

    @BeforeAll
    static void load() {
        PG.start();
        JdbcRegionalSubwayTimetableStore store = new JdbcRegionalSubwayTimetableStore(
                new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword())));
        RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
        RegionalSubwayTimetableProperties properties = new RegionalSubwayTimetableProperties();
        properties.setLines(new LinkedHashSet<>(List.of("DG1", "DG2", "DG3", "GJ1")));
        loaded = new RegionalSubwayTimetableLoadService(catalog, store, properties,
                List.of(new DaeguExportFileSource(new ObjectMapper(), catalog), new GwangjuCsvSource()), Clock.systemUTC()).load();
        HolidayCalendar calendar = new HolidayCalendar() {
            @Override public boolean isHoliday(LocalDate d) { return HOLIDAYS.containsKey(d); }
            @Override public Optional<String> holidayName(LocalDate d) { return Optional.ofNullable(HOLIDAYS.get(d)); }
        };
        query = new RegionalDepartureQueryService(catalog, store, calendar);
    }

    @AfterAll
    static void stop() {
        PG.stop();
    }

    @Test
    @DisplayName("대구·광주 114역 전부 적재, 모르는 종착역 0")
    void loadedAll() {
        assertThat(loaded.stations()).isEqualTo(35 + 29 + 30 + 20);
        assertThat(loaded.loaded()).isEqualTo(loaded.stations());
        assertThat(loaded.failed()).isZero();
        assertThat(loaded.unmatchedTerminal()).isZero();
    }

    @Test
    @DisplayName("반월당(2호선) 문양 방면 일요일 첫차 05:42, 토요일 08시대 = 공식 API(08:00 08:09 08:18 ...)")
    void banwoldangOfficial() {
        RegionalDeparturesView sun = query.next("DG2-230", 28, 1, Instant.parse("2026-11-21T19:00:00Z")).orElseThrow();   // 일 04:00
        assertThat(sun.dayType()).isEqualTo(RegionalDayType.HO);
        assertThat(sun.directions().get(0).departures().get(0).scheduledAt()).isEqualTo(Instant.parse("2026-11-21T20:42:00Z"));

        RegionalDeparturesView sat = query.next("DG2-230", 28, 3, Instant.parse("2026-11-20T22:59:00Z")).orElseThrow();   // 토 07:59
        assertThat(sat.timetableDayType()).isEqualTo(RegionalDayType.SA);
        assertThat(sat.directions().get(0).departures()).extracting(d -> d.scheduledAt().toString())
                .containsExactly("2026-11-20T23:00:00Z", "2026-11-20T23:09:00Z", "2026-11-20T23:18:00Z");
    }

    @Test
    @DisplayName("반월당(2호선) 영남대 방향으로 가며 '이곡행' 은 내릴 역이 이곡 너머면 빠진다 — 반대로 문양 방향 이곡 이전 하차면 포함")
    void shortTurn() {
        // 이곡은 대구2 시드에서 문양 쪽. 반월당 → 이곡 이전(죽전) 하차는 이곡행 포함, 이곡 너머(문양) 하차는 제외
        var catalog = new RegionalStationSeedCatalog(new ObjectMapper());
        int igok = catalog.findByName("DG2", "이곡").seq();
        int jukjeon = catalog.findByName("DG2", "죽전").seq();
        Instant sun2330 = Instant.parse("2026-11-22T14:30:00Z");   // 일 23:30
        assertThat(query.next("DG2-230", jukjeon, 5, sun2330).orElseThrow().directions().get(0).departures())
                .extracting(RegionalDeparturesView.Departure::terminalName).contains("이곡");
        assertThat(query.next("DG2-230", 28, 5, sun2330).orElseThrow().directions().get(0).departures())
                .extracting(RegionalDeparturesView.Departure::terminalName).doesNotContain("이곡");
        assertThat(igok).isGreaterThan(jukjeon);
    }

    @Test
    @DisplayName("광주 추석(2026-09-25)은 명절표 — 휴일표와 다른 열차가 나온다")
    void gwangjuLunarHoliday() {
        RegionalDeparturesView v = query.next("GJ1-112", null, 3, Instant.parse("2026-09-25T01:00:00Z")).orElseThrow();   // 10:00
        assertThat(v.dayType()).isEqualTo(RegionalDayType.MH);
        assertThat(v.timetableDayType()).isEqualTo(RegionalDayType.MH);
        assertThat(v.directions()).hasSize(2);
    }
}
