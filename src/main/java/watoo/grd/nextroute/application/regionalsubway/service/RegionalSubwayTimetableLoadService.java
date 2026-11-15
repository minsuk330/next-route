package watoo.grd.nextroute.application.regionalsubway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalSubwayLoadResult;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSubwayTimetableStore;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSubwayTimetableStore.StoredDeparture;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 지방 지하철 공식 시간표 적재.
 *
 * <p>순서: 시드 역 upsert → 역마다 그 노선의 원천에서 받기 → 종착역 이름을 시드 seq 로 → 역 단위 통째 교체.
 *
 * <h2>실패를 다루는 방식</h2>
 * <ul>
 *   <li>역 하나 실패 → 그 역은 옛 값 그대로. 저장은 역 단위 통째 교체라 반쯤 쓴 역이 없다.</li>
 *   <li>0건 → 실패로 치지 않지만 옛 값을 지우지도 않는다(일시적 빈 응답일 수 있다).</li>
 *   <li>포털 공통 오류(키 미등록 등) → 같은 원천의 남은 역은 건너뛴다. 다른 원천은 계속한다.</li>
 *   <li>종착역 이름을 시드에서 못 찾은 열차 → 그 열차만 버리고 센다. 방향을 모르는 열차를 넣지 않는다.</li>
 * </ul>
 */
@Slf4j
@Service
public class RegionalSubwayTimetableLoadService {

    private final RegionalStationSeedCatalog catalog;
    private final RegionalSubwayTimetableStore store;
    private final RegionalSubwayTimetableProperties properties;
    private final Map<String, RegionalTimetableSource> sourceByLine;
    private final Clock clock;
    private final Sleeper sleeper;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Autowired
    public RegionalSubwayTimetableLoadService(RegionalStationSeedCatalog catalog,
                                              RegionalSubwayTimetableStore store,
                                              RegionalSubwayTimetableProperties properties,
                                              List<RegionalTimetableSource> sources,
                                              Clock clock) {
        this(catalog, store, properties, sources, clock, Thread::sleep);
    }

    RegionalSubwayTimetableLoadService(RegionalStationSeedCatalog catalog,
                                       RegionalSubwayTimetableStore store,
                                       RegionalSubwayTimetableProperties properties,
                                       List<RegionalTimetableSource> sources,
                                       Clock clock,
                                       Sleeper sleeper) {
        this.catalog = catalog;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
        this.sleeper = sleeper;
        Map<String, RegionalTimetableSource> map = new HashMap<>();
        for (RegionalTimetableSource source : sources) {
            for (String line : source.lineCodes()) {
                RegionalTimetableSource prev = map.put(line, source);
                if (prev != null) {
                    throw new IllegalStateException("line " + line + " has two sources: "
                            + prev.sourceCode() + ", " + source.sourceCode());
                }
            }
        }
        this.sourceByLine = Map.copyOf(map);
    }

    public boolean isEmpty() {
        return store.isEmpty();
    }

    public RegionalSubwayLoadResult load() {
        if (!running.compareAndSet(false, true)) {
            log.info("[RegionalSubway] already running; skip");
            return RegionalSubwayLoadResult.skippedRun();
        }
        try {
            RegionalSubwayLoadResult result;
            try {
                result = run();
            } catch (RuntimeException e) {
                log.warn("[RegionalSubway] load aborted: {}", e.toString());
                return RegionalSubwayLoadResult.skippedRun();
            }
            log.info("[RegionalSubway] stations={} loaded={} empty={} failed={} unmatchedTerminal={} rows={} elapsed={}s",
                    result.stations(), result.loaded(), result.empty(), result.failed(),
                    result.unmatchedTerminal(), result.rows(), result.elapsed().toSeconds());
            return result;
        } finally {
            running.set(false);
        }
    }

    private RegionalSubwayLoadResult run() {
        Instant startedAt = clock.instant();
        Set<String> lines = new LinkedHashSet<>(properties.getLines());
        List<RegionalStationSeed> targets = catalog.all().stream()
                .filter(s -> lines.contains(s.lineCode()))
                .toList();
        store.upsertStations(targets, startedAt);

        int loaded = 0;
        int empty = 0;
        int failed = 0;
        int unmatched = 0;
        int rows = 0;
        Set<String> blockedSources = new HashSet<>();
        for (RegionalStationSeed station : targets) {
            RegionalTimetableSource source = sourceByLine.get(station.lineCode());
            if (source == null) {
                log.warn("[RegionalSubway] no source for line={}", station.lineCode());
                failed++;
                continue;
            }
            if (blockedSources.contains(source.sourceCode())) {
                failed++;
                continue;
            }
            List<RegionalDeparture> fetched;
            try {
                fetched = fetchWithRetry(source, station);
            } catch (RegionalSourceException e) {
                failed++;
                if (e.blocking()) {
                    blockedSources.add(source.sourceCode());
                    log.warn("[RegionalSubway] source={} blocking error, skip remaining: {}", source.sourceCode(), e.getMessage());
                } else {
                    log.warn("[RegionalSubway] station={} source={} failed: {}", station.stationKey(), source.sourceCode(), e.getMessage());
                }
                continue;
            } catch (RuntimeException e) {
                failed++;
                log.warn("[RegionalSubway] station={} source={} unexpected: {}", station.stationKey(), source.sourceCode(), e.toString());
                continue;
            }
            Converted converted = convert(station, fetched);
            unmatched += converted.unmatched();
            if (converted.departures().isEmpty()) {
                empty++;
                log.info("[RegionalSubway] station={} source={} empty (fetched={}, unmatched={}); keep old rows",
                        station.stationKey(), source.sourceCode(), fetched.size(), converted.unmatched());
                continue;
            }
            try {
                store.replaceDepartures(station.stationKey(), converted.departures(), source.sourceCode(),
                        source.sourceDate(), startedAt);
                loaded++;
                rows += converted.departures().size();
            } catch (RuntimeException e) {
                failed++;
                log.warn("[RegionalSubway] station={} store failed: {}", station.stationKey(), e.toString());
            }
        }
        return new RegionalSubwayLoadResult(targets.size(), loaded, empty, failed, unmatched, rows, false,
                Duration.between(startedAt, clock.instant()));
    }

    private List<RegionalDeparture> fetchWithRetry(RegionalTimetableSource source, RegionalStationSeed station) {
        int attempt = 0;
        while (true) {
            try {
                List<RegionalDeparture> result = source.fetch(station);
                pause(source);
                return result;
            } catch (RegionalSourceException e) {
                pause(source);
                if (!e.retryable() || attempt >= properties.getMaxRetries()) {
                    throw e;
                }
                attempt++;
                sleep(properties.getRetryBackoffMs() * attempt);
            }
        }
    }

    private void pause(RegionalTimetableSource source) {
        if (source.remote()) {
            sleep(properties.getCallIntervalMs());
        }
    }

    private void sleep(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            sleeper.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw RegionalSourceException.failed("interrupted");
        }
    }

    /** 종착역 이름 → seq. 같은 (요일, 시각, 종착) 은 하나로. */
    Converted convert(RegionalStationSeed station, List<RegionalDeparture> fetched) {
        Set<StoredDeparture> out = new LinkedHashSet<>();
        int unmatched = 0;
        for (RegionalDeparture d : fetched) {
            RegionalStationSeed terminal = catalog.findByName(station.lineCode(), d.terminalName());
            if (terminal == null) {
                unmatched++;
                continue;
            }
            if (terminal.seq() == station.seq()) {
                continue;   // 이 역이 종착 = 타는 열차가 아니다(도착 시각)
            }
            out.add(new StoredDeparture(d.dayType().name(), d.depSeconds(), terminal.stationName(), terminal.seq()));
        }
        if (unmatched > 0) {
            log.debug("[RegionalSubway] station={} unmatched terminals={}", station.stationKey(), unmatched);
        }
        return new Converted(new ArrayList<>(out), unmatched);
    }

    record Converted(List<StoredDeparture> departures, int unmatched) {
    }

    @FunctionalInterface
    interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }
}
