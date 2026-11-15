package watoo.grd.nextroute.application.regionalsubway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalSubwayLoadResult;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSubwayTimetableStore;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class RegionalSubwayTimetableLoadServiceTest {

    // 일직선 반석(0) - 지족(1) - 노은(2) - 판암(3)
    private static RegionalStationSeed seed(String name, int seq) {
        return new RegionalStationSeed("DJ1-00" + seq, "DJ1", "대전1호선", name, seq, "T" + seq, "00" + seq, "g" + seq, 0, 0);
    }

    private final List<RegionalStationSeed> seeds = List.of(seed("반석", 0), seed("지족", 1), seed("노은", 2), seed("판암", 3));
    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(seeds);
    private final FakeStore store = new FakeStore();
    private final RegionalSubwayTimetableProperties properties = new RegionalSubwayTimetableProperties();

    {
        properties.setLines(new LinkedHashSet<>(List.of("DJ1")));
        properties.setRetryBackoffMs(0);
        properties.setCallIntervalMs(0);
    }

    private RegionalSubwayTimetableLoadService service(Function<RegionalStationSeed, List<RegionalDeparture>> fetch) {
        RegionalTimetableSource source = new RegionalTimetableSource() {
            @Override public Set<String> lineCodes() { return Set.of("DJ1"); }
            @Override public String sourceCode() { return "TAGO"; }
            @Override public List<RegionalDeparture> fetch(RegionalStationSeed s) { return fetch.apply(s); }
        };
        return new RegionalSubwayTimetableLoadService(catalog, store, properties, List.of(source),
                Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC), ms -> { });
    }

    @Test
    @DisplayName("종착역 이름 → seq. 이 역에서 끝나는 열차와 모르는 종착역은 버리고, 같은 열차는 하나로")
    void convertsTerminals() {
        RegionalSubwayLoadResult r = service(s -> s.seq() != 1 ? List.of() : List.of(
                new RegionalDeparture(RegionalDayType.WD, 20000, "판암"),
                new RegionalDeparture(RegionalDayType.WD, 20000, "판암역"),   // 같은 열차(정규화)
                new RegionalDeparture(RegionalDayType.WD, 21000, "반석(칠성대)"),
                new RegionalDeparture(RegionalDayType.HO, 22000, "지족"),     // 이 역 종착 = 도착
                new RegionalDeparture(RegionalDayType.HO, 23000, "#scode:9")  // 모르는 종착
        )).load();

        assertThat(store.departures.get("DJ1-001")).containsExactly(
                new RegionalSubwayTimetableStore.StoredDeparture("WD", 20000, "판암", 3),
                new RegionalSubwayTimetableStore.StoredDeparture("WD", 21000, "반석", 0));
        assertThat(r.unmatchedTerminal()).isEqualTo(1);
        assertThat(r.loaded()).isEqualTo(1);
        assertThat(r.empty()).isEqualTo(3);
        assertThat(store.upserted).hasSize(4);
    }

    @Test
    @DisplayName("역 하나 실패는 그 역만 옛 값 유지, 일시 오류는 재시도, 포털 blocking 은 남은 역 건너뜀")
    void failures() {
        int[] calls = new int[4];
        RegionalSubwayLoadResult r = service(s -> {
            calls[s.seq()]++;
            if (s.seq() == 0 && calls[0] == 1) throw RegionalSourceException.retryable("timeout");
            if (s.seq() == 1) throw RegionalSourceException.failed("bad");
            if (s.seq() == 2) throw RegionalSourceException.blocking("key");
            return List.of(new RegionalDeparture(RegionalDayType.WD, 20000, "판암"));
        }).load();

        assertThat(calls).containsExactly(2, 1, 1, 0);   // A 재시도 1회, D 는 blocking 뒤라 안 부름
        assertThat(store.departures).containsOnlyKeys("DJ1-000");
        assertThat(r.failed()).isEqualTo(3);
        assertThat(r.loaded()).isEqualTo(1);
    }

    static class FakeStore implements RegionalSubwayTimetableStore {
        final List<RegionalStationSeed> upserted = new ArrayList<>();
        final Map<String, List<StoredDeparture>> departures = new HashMap<>();

        @Override public void upsertStations(List<RegionalStationSeed> stations, Instant loadedAt) { upserted.addAll(stations); }
        @Override public void replaceDepartures(String stationKey, List<StoredDeparture> d, String source, LocalDate date, Instant at) {
            departures.put(stationKey, List.copyOf(d));
        }
        @Override public boolean isEmpty() { return departures.isEmpty(); }
    }
}
