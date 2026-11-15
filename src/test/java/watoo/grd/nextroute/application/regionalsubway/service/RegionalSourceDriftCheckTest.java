package watoo.grd.nextroute.application.regionalsubway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway.DaeguExportFileSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalSourceDriftCheckTest {

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private final DaeguExportFileSource daegu = new DaeguExportFileSource(new ObjectMapper(), catalog);
    private final RegionalSubwayTimetableProperties properties = new RegionalSubwayTimetableProperties();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    {
        properties.setCallIntervalMs(0);
    }

    @Test
    @DisplayName("TAGO 가 파일과 같으면 0, 대구가 개정해 TAGO 만 바뀐 역은 '다시 받기' 대상으로 센다")
    void daeguDrift() {
        // TAGO 흉내: 파일 값 그대로, 반월당(DG2-230)만 평일 전부 2분 늦춤(개정)
        RegionalSourceDriftCheck check = new RegionalSourceDriftCheck(catalog, List.of(daegu), s -> {
            List<RegionalDeparture> same = new ArrayList<>(daegu.fetch(s).stream()
                    .filter(d -> d.dayType() != RegionalDayType.SA).toList());
            if (s.stationKey().equals("DG2-230")) {
                same.replaceAll(d -> d.dayType() == RegionalDayType.WD
                        ? new RegionalDeparture(d.dayType(), d.depSeconds() + 120, d.terminalName()) : d);
            }
            return same;
        }, id -> Optional.empty(), properties, registry);

        assertThat(check.checkDaegu()).isEqualTo(1);
        assertThat(registry.get("regional_subway_source_drift_stations").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("광주 데이터셋 수정일이 커밋한 파일(2026-11-02)보다 새로우면 경고 게이지 1")
    void gwangjuOutdated() {
        RegionalSourceDriftCheck stale = new RegionalSourceDriftCheck(catalog, List.of(), s -> List.of(),
                id -> Optional.of(LocalDate.of(2026, 12, 1)), properties, registry);
        assertThat(stale.checkGwangju()).isTrue();
        assertThat(registry.get("regional_subway_source_outdated").gauge().value()).isEqualTo(1.0);

        RegionalSourceDriftCheck same = new RegionalSourceDriftCheck(catalog, List.of(), s -> List.of(),
                id -> Optional.of(LocalDate.of(2026, 11, 2)), properties, new SimpleMeterRegistry());
        assertThat(same.checkGwangju()).isFalse();
    }

    @Test
    @DisplayName("60초 창 일치율 — 1분 차이는 같은 열차")
    void matchRatio() {
        assertThat(RegionalSourceDriftCheck.matchRatio(Set.of(100, 200), Set.of(101, 200))).isEqualTo(1.0);
        assertThat(RegionalSourceDriftCheck.matchRatio(Set.of(100, 200), Set.of(103, 200))).isEqualTo(0.5);
        assertThat(RegionalSourceDriftCheck.matchRatio(Set.of(), Set.of())).isEqualTo(1.0);
    }
}
