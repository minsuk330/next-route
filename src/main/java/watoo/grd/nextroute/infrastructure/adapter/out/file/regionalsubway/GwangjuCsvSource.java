package watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalTimetableText;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 광주교통공사 열차 시간표 데이터(data.go.kr 15111497, 기준일 2026-11-01) — 광주 1호선.
 *
 * <p>헤더 {@code 요일,종착역 코드,방향(상_하행),도착시간,역사코드,기준일자,호선,종착역명,역사명}.
 * 요일은 평일/토요일/휴일/명절 4종(평일 일부 행은 끝에 공백). {@code 역사코드} 는 역과 1:1 이 아니라 역명으로 맞춘다.
 */
@Component
public class GwangjuCsvSource implements RegionalTimetableSource {

    static final String RESOURCE = "subway/gwangju/timetable.csv";
    private static final Set<String> LINES = Set.of("GJ1");

    private final Map<String, List<RegionalDeparture>> byStationName;   // 정규화 역명 → 출발들
    private final LocalDate sourceDate;

    public GwangjuCsvSource() {
        this(readLines());
    }

    GwangjuCsvSource(List<String> lines) {
        this.byStationName = new HashMap<>();
        LocalDate date = null;
        boolean header = true;
        for (String line : lines) {
            if (header) {
                header = false;
                continue;
            }
            String[] c = line.split(",", -1);
            if (c.length < 9) {
                continue;
            }
            RegionalDayType dayType = switch (c[0].trim()) {
                case "평일" -> RegionalDayType.WD;
                case "토요일" -> RegionalDayType.SA;
                case "휴일" -> RegionalDayType.HO;
                case "명절" -> RegionalDayType.MH;
                default -> null;
            };
            OptionalInt seconds = RegionalTimetableText.serviceSeconds(c[3]);
            if (dayType == null || seconds.isEmpty()) {
                continue;
            }
            if (date == null && !c[5].isBlank()) {
                try {
                    date = LocalDate.parse(c[5].trim());
                } catch (RuntimeException ignored) {
                    // 기준일자 형식이 바뀌어도 기동은 막지 않는다
                }
            }
            byStationName.computeIfAbsent(RegionalTimetableText.normalizeName(c[8]), k -> new ArrayList<>())
                    .add(new RegionalDeparture(dayType, seconds.getAsInt(), c[7].trim()));
        }
        this.sourceDate = date;
    }

    @Override
    public Set<String> lineCodes() {
        return LINES;
    }

    @Override
    public String sourceCode() {
        return "GWANGJU_CSV";
    }

    @Override
    public LocalDate sourceDate() {
        return sourceDate;
    }

    @Override
    public boolean remote() {
        return false;
    }

    @Override
    public List<RegionalDeparture> fetch(RegionalStationSeed station) {
        List<RegionalDeparture> rows = byStationName.get(RegionalTimetableText.normalizeName(station.stationName()));
        if (rows == null) {
            throw RegionalSourceException.failed("gwangju csv has no rows for " + station.stationName());
        }
        return rows;
    }

    private static List<String> readLines() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            return r.lines().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("gwangju timetable read failed", e);
        }
    }
}
