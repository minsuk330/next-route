package watoo.grd.nextroute.application.regionalsubway.service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayTimetableProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.PublicDataCatalogPort;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTagoCrossCheckPort;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 커밋한 파일 원천이 낡았는지 주 1회 확인한다. 값은 바꾸지 않고 경고만 낸다(게이지 + 로그).
 *
 * <ul>
 *   <li>대구: 내보내기 파일의 평일·공휴일을 같은 역 TAGO 와 분 단위로 비교. 둘은 2026-11-16 에 360건 모두 같았다.
 *       대구가 시간표를 바꾸면 TAGO 가 따라가고 파일은 그대로라 어긋난다 → "대구 파일 다시 받기".
 *       토요일만 바뀌는 개정은 못 잡는다(TAGO 에 토요일이 없다).</li>
 *   <li>광주: TAGO 가 늦게 반영해(11-01 추가 열차 누락) 비교하면 오탐이다. data.go.kr 수정일만 본다.</li>
 * </ul>
 */
@Slf4j
@Service
public class RegionalSourceDriftCheck {

    static final String GWANGJU_DATASET = "15111497";
    static final LocalDate GWANGJU_COMMITTED_MODIFIED = LocalDate.of(2026, 11, 2);   // resources/subway/gwangju/SOURCE.md
    static final double MATCH_THRESHOLD = 0.95;

    private final RegionalStationSeedCatalog catalog;
    private final List<RegionalTimetableSource> sources;
    private final RegionalTagoCrossCheckPort tago;
    private final PublicDataCatalogPort publicData;
    private final RegionalSubwayTimetableProperties properties;
    private final AtomicInteger daeguDriftStations = new AtomicInteger();
    private final AtomicInteger gwangjuOutdated = new AtomicInteger();

    public RegionalSourceDriftCheck(RegionalStationSeedCatalog catalog,
                                    List<RegionalTimetableSource> sources,
                                    RegionalTagoCrossCheckPort tago,
                                    PublicDataCatalogPort publicData,
                                    RegionalSubwayTimetableProperties properties,
                                    MeterRegistry meterRegistry) {
        this.catalog = catalog;
        this.sources = sources;
        this.tago = tago;
        this.publicData = publicData;
        this.properties = properties;
        Gauge.builder("regional_subway_source_drift_stations", daeguDriftStations, AtomicInteger::get)
                .description("파일 원천과 TAGO 평일·휴일이 95% 미만 맞는 역 수(대구 파일 다시 받기)")
                .tag("source", "DAEGU_API").register(meterRegistry);
        Gauge.builder("regional_subway_source_outdated", gwangjuOutdated, AtomicInteger::get)
                .description("data.go.kr 수정일이 커밋한 파일보다 새로우면 1")
                .tag("source", "GWANGJU_CSV").register(meterRegistry);
    }

    public void run() {
        checkDaegu();
        checkGwangju();
    }

    int checkDaegu() {
        Optional<RegionalTimetableSource> daegu = sources.stream().filter(s -> "DAEGU_API".equals(s.sourceCode())).findFirst();
        if (daegu.isEmpty()) {
            return 0;
        }
        List<String> drifted = new ArrayList<>();
        for (RegionalStationSeed station : catalog.all()) {
            if (!station.lineCode().startsWith("DG") || station.tagoStationId() == null) {
                continue;
            }
            try {
                List<RegionalDeparture> file = daegu.get().fetch(station);
                List<RegionalDeparture> other = tago.fetchTago(station);
                pause();
                for (RegionalDayType day : List.of(RegionalDayType.WD, RegionalDayType.HO)) {
                    double ratio = matchRatio(minutes(file, day), minutes(other, day));
                    if (ratio < MATCH_THRESHOLD) {
                        drifted.add(station.stationKey() + ":" + day + "=" + String.format("%.2f", ratio));
                    }
                }
            } catch (RegionalSourceException e) {
                log.warn("[RegionalSubwayDrift] station={} check skipped: {}", station.stationKey(), e.getMessage());
                if (e.blocking()) {
                    break;
                }
            }
        }
        int stations = (int) drifted.stream().map(s -> s.substring(0, s.indexOf(':'))).distinct().count();
        daeguDriftStations.set(stations);
        if (stations > 0) {
            log.warn("[RegionalSubwayDrift] 대구 파일이 TAGO 와 어긋난 역 {}곳 — 대구 파일 다시 받기(tools/regional-subway/daegu_export.js): {}",
                    stations, drifted.stream().limit(20).toList());
        } else {
            log.info("[RegionalSubwayDrift] 대구 파일 = TAGO 평일·휴일");
        }
        return stations;
    }

    boolean checkGwangju() {
        Optional<LocalDate> modified = publicData.fileDataModified(GWANGJU_DATASET);
        boolean outdated = modified.map(d -> d.isAfter(GWANGJU_COMMITTED_MODIFIED)).orElse(false);
        gwangjuOutdated.set(outdated ? 1 : 0);
        if (outdated) {
            log.warn("[RegionalSubwayDrift] 광주 시간표 데이터셋이 갱신됨 modified={} committed={} — resources/subway/gwangju 교체",
                    modified.get(), GWANGJU_COMMITTED_MODIFIED);
        }
        return outdated;
    }

    static Set<Integer> minutes(List<RegionalDeparture> departures, RegionalDayType day) {
        return departures.stream().filter(d -> d.dayType() == day)
                .map(d -> d.depSeconds() / 60)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /** 60초 창: 같은 분, 앞뒤 1분까지 같은 열차로 본다. 둘 다 비면 1. */
    static double matchRatio(Set<Integer> a, Set<Integer> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 1.0;
        }
        long matched = a.stream().filter(m -> b.contains(m) || b.contains(m - 1) || b.contains(m + 1)).count();
        return (double) matched / Math.max(a.size(), b.size());
    }

    private void pause() {
        try {
            Thread.sleep(Math.max(0, properties.getCallIntervalMs()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
