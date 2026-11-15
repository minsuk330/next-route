package watoo.grd.nextroute.application.regionalsubway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalDepartureReadPort.Row;
import watoo.grd.nextroute.application.subway.service.HolidayCalendar;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalDepartureQueryServiceTest {

    // 대구1 축약: 하양(0) - 안심(1) - 반월당(2) - 설화명곡(3). 안심 종착 단축 열차가 있다
    private static RegionalStationSeed seed(String key, String name, int seq) {
        return new RegionalStationSeed(key, "DG1", "대구1호선", name, seq, null, key.substring(4), "g" + seq, 35.8, 128.6);
    }

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(List.of(
            seed("DG1-149", "하양", 0), seed("DG1-146", "안심", 1), seed("DG1-130", "반월당", 2), seed("DG1-115", "설화명곡", 3)));
    private final Map<String, List<Row>> rows = new HashMap<>();
    private final HolidayCalendar noHolidays = date -> false;
    private final RegionalDepartureQueryService service =
            new RegionalDepartureQueryService(catalog, (key, day) -> rows.getOrDefault(key + "|" + day, List.of()), noHolidays);

    private static int hm(int h, int m) {
        return h * 3600 + m * 60;
    }

    @Test
    @DisplayName("내릴 역 하양(0) 이면 안심(1) 종착 열차는 빠진다. 역 화면은 두 방향, 이 역에서 끝나는 행은 적재에서 이미 빠져 있다")
    void excludesTrainsEndingBeforeAlight() {
        rows.put("DG1-130|WD", List.of(
                new Row(hm(8, 0), "안심", 1), new Row(hm(8, 5), "하양", 0), new Row(hm(8, 7), "설화명곡", 3)));
        Instant mon0759 = Instant.parse("2026-11-15T22:59:00Z");   // 2026-11-16(월) 07:59 KST

        RegionalDeparturesView toHayang = service.next("DG1-130", 0, 3, mon0759).orElseThrow();
        assertThat(toHayang.directions()).hasSize(1);
        // 오늘 하양행은 08:05 하나, 모자란 칸은 다음 서비스일(화) 하양행으로 채운다. 안심행은 어느 날이든 빠진다
        assertThat(toHayang.directions().get(0).departures()).extracting(RegionalDeparturesView.Departure::terminalName)
                .containsExactly("하양", "하양");
        assertThat(toHayang.directions().get(0).departures().get(1).scheduledAt()).isEqualTo(Instant.parse("2026-11-16T23:05:00Z"));
        assertThat(toHayang.directions().get(0).departures().get(0).minutesUntil()).isEqualTo(6);

        RegionalDeparturesView station = service.next("DG1-130", null, 2, mon0759).orElseThrow();
        assertThat(station.directions()).extracting(RegionalDeparturesView.Direction::towardTerminal)
                .containsExactly("하양", "설화명곡");
        assertThat(station.directions().get(0).departures()).extracting(RegionalDeparturesView.Departure::terminalName)
                .containsExactly("안심", "하양");
        assertThat(station.dayType()).isEqualTo(RegionalDayType.WD);
    }

    @Test
    @DisplayName("막차 뒤(토 23:50)는 다음 서비스일 요일을 다시 판정해 일요일 공휴일표 첫차로 채운다")
    void afterLastTrainUsesNextServiceDay() {
        rows.put("DG1-130|SA", List.of(new Row(hm(23, 40), "설화명곡", 3)));
        rows.put("DG1-130|HO", List.of(new Row(hm(5, 47), "설화명곡", 3)));
        rows.put("DG1-130|WD", List.of(new Row(hm(5, 30), "설화명곡", 3)));
        Instant sat2350 = Instant.parse("2026-11-21T14:50:00Z");   // 2026-11-21(토) 23:50 KST

        RegionalDeparturesView v = service.next("DG1-130", 3, 1, sat2350).orElseThrow();
        RegionalDeparturesView.Departure first = v.directions().get(0).departures().get(0);
        assertThat(first.scheduledAt()).isEqualTo(Instant.parse("2026-11-21T20:47:00Z"));   // 일 05:47 KST
        assertThat(v.dayType()).isEqualTo(RegionalDayType.SA);
    }

    @Test
    @DisplayName("04:00 전은 전날 서비스일 — 일 00:10 에는 토요일표의 24:16(=다음 날 00:16) 열차가 보인다")
    void earlyMorningBelongsToPreviousServiceDay() {
        rows.put("DG1-130|SA", List.of(new Row(86400 + hm(0, 16), "설화명곡", 3)));
        Instant sun0010 = Instant.parse("2026-11-21T15:10:00Z");   // 2026-11-22(일) 00:10 KST

        RegionalDeparturesView v = service.next("DG1-130", 3, 1, sun0010).orElseThrow();
        assertThat(v.directions().get(0).departures().get(0).minutesUntil()).isEqualTo(6);
    }

    @Test
    @DisplayName("대전 토요일은 공식이 공휴일 한 표 → timetableDayType=HO. 노선 끝 역의 바깥 방향은 내지 않는다")
    void daejeonSaturdayUsesHoliday() {
        RegionalStationSeedCatalog dj = new RegionalStationSeedCatalog(List.of(
                new RegionalStationSeed("DJ1-001", "DJ1", "대전1호선", "판암", 0, null, "001", "g0", 36.3, 127.4),
                new RegionalStationSeed("DJ1-022", "DJ1", "대전1호선", "반석", 1, null, "022", "g1", 36.3, 127.4)));
        Map<String, List<Row>> djRows = Map.of("DJ1-001|HO", List.of(new Row(hm(9, 0), "반석", 1)));
        RegionalDepartureQueryService djService = new RegionalDepartureQueryService(dj,
                (key, day) -> djRows.getOrDefault(key + "|" + day, List.of()), noHolidays);

        RegionalDeparturesView v = djService.next("DJ1-001", null, 3, Instant.parse("2026-11-21T00:00:00Z")).orElseThrow();
        assertThat(v.dayType()).isEqualTo(RegionalDayType.SA);
        assertThat(v.timetableDayType()).isEqualTo(RegionalDayType.HO);
        assertThat(v.directions()).extracting(RegionalDeparturesView.Direction::towardTerminal).containsExactly("반석");
    }

    @Test
    @DisplayName("모르는 역은 empty")
    void unknownStation() {
        assertThat(service.next("BS1-119", null, 3, Instant.now())).isEmpty();
        assertThat(LocalDate.of(2026, 11, 21).getDayOfWeek().getValue()).isEqualTo(6);
    }
}
