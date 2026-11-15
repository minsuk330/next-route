package watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** 커밋된 광주교통공사 공식 CSV(기준일 2026-11-01). 기대값은 원본 파일 집계. */
class GwangjuCsvSourceTest {

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private final GwangjuCsvSource source = new GwangjuCsvSource();

    @Test
    @DisplayName("운천역 4요일 열차 수(시각+종착 기준, 양방향 합): 평일 240, 토요일 206, 휴일 202, 명절 162 — 기준일 2026-11-01")
    void uncheonCounts() {
        List<RegionalDeparture> rows = source.fetch(catalog.findByName("GJ1", "운천"));
        Map<RegionalDayType, Long> counts = rows.stream()
                .map(d -> d.dayType() + "|" + d.depSeconds() + "|" + d.terminalName())
                .distinct()
                .collect(Collectors.groupingBy(k -> RegionalDayType.valueOf(k.split("\\|")[0]), Collectors.counting()));
        assertThat(counts).containsEntry(RegionalDayType.WD, 240L)
                .containsEntry(RegionalDayType.SA, 206L)
                .containsEntry(RegionalDayType.HO, 202L)
                .containsEntry(RegionalDayType.MH, 162L);
        assertThat(source.sourceDate()).isEqualTo(LocalDate.of(2026, 11, 1));
    }

    @Test
    @DisplayName("CSV 역명·종착역명의 '역' 은 시드 역명과 맞는다(역사코드는 쓰지 않는다)")
    void namesMatchSeed() {
        for (var s : catalog.line("GJ1")) {
            for (RegionalDeparture d : source.fetch(s)) {
                assertThat(catalog.findByName("GJ1", d.terminalName()))
                        .as("%s 종착 %s", s.stationName(), d.terminalName()).isNotNull();
            }
        }
    }
}
