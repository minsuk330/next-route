package watoo.grd.nextroute.infrastructure.adapter.out.file.regionalsubway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDayType;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparture;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalStationSeed;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalSourceException;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalTimetableSource;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalStationSeedCatalog;
import watoo.grd.nextroute.application.regionalsubway.service.RegionalTimetableText;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;

/**
 * 대구교통공사 열차시각 API 를 브라우저로 내보낸 파일({@code resources/subway/daegu/timetable.json}) — 대구 1~3호선.
 *
 * <p>API({@code dtro.or.kr/.../stationTime.php})는 서버 호출에 봇 확인 스크립트를 돌려줘서 서버가 직접 부르지 않는다.
 * 사람이 {@code tools/regional-subway/daegu_export.js} 로 받아 커밋한다(SOURCE.md).
 *
 * <p>행 하나 = 역 × 방향(UP/DOWN) × 요일(WEEKDAY/SAT/HOLIDAY). {@code schedule} 항목 {@code "23:45:00(이곡)"}:
 * 괄호 = 그 열차의 종착역, 없으면 그 방향의 노선 끝 역. 종착역의 반대 방향은 {@code ["-"]}.
 * UP/DOWN 이 노선 순서(seq)의 어느 쪽인지는 노선 첫 역(seq 0)에서 열차가 있는 방향으로 정하고 마지막 역에서 거꾸로 확인한다.
 */
@Slf4j
@Component
public class DaeguExportFileSource implements RegionalTimetableSource {

    static final String RESOURCE = "subway/daegu/timetable.json";
    private static final Set<String> LINES = Set.of("DG1", "DG2", "DG3");

    private final RegionalStationSeedCatalog catalog;
    private final Map<String, List<Row>> rowsByStation;   // "DG2-230" → 행들
    private final Map<String, String> increasingMeth;     // "DG1" → "UP" (seq 가 커지는 쪽)
    private final LocalDate sourceDate;

    @Autowired
    public DaeguExportFileSource(ObjectMapper objectMapper, RegionalStationSeedCatalog catalog) {
        this(read(objectMapper), catalog);
    }

    DaeguExportFileSource(JsonNode root, RegionalStationSeedCatalog catalog) {
        this.catalog = catalog;
        this.rowsByStation = new HashMap<>();
        for (JsonNode r : root.path("rows")) {
            JsonNode data = r.path("data");
            if (!data.isArray() || data.isEmpty()) {
                continue;
            }
            JsonNode d = data.get(0);
            String lineCode = "DG" + r.path("line").asText();
            String key = lineCode + "-" + d.path("sttCd").asText();
            List<String> schedule = new ArrayList<>();
            d.path("schedule").forEach(x -> schedule.add(x.asText()));
            rowsByStation.computeIfAbsent(key, k -> new ArrayList<>())
                    .add(new Row(r.path("meth").asText(), r.path("type").asText(), schedule));
        }
        this.increasingMeth = new HashMap<>();
        for (String line : LINES) {
            try {
                increasingMeth.put(line, resolveIncreasingMeth(line));
            } catch (RuntimeException e) {
                // 파일이 깨져도 기동은 막지 않는다. 그 노선 fetch 가 실패 → 적재가 옛 값을 유지한다
                log.error("[RegionalSubway] daegu export unusable for {}: {}", line, e.getMessage());
            }
        }
        this.sourceDate = parseDate(root.path("fetchedAt").asText(null));
    }

    @Override
    public Set<String> lineCodes() {
        return LINES;
    }

    @Override
    public String sourceCode() {
        return "DAEGU_API";
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
        List<Row> rows = rowsByStation.get(station.stationKey());
        if (rows == null) {
            throw RegionalSourceException.failed("daegu export has no rows for " + station.stationKey());
        }
        List<RegionalStationSeed> line = catalog.line(station.lineCode());
        String first = line.get(0).stationName();
        String last = line.get(line.size() - 1).stationName();
        String up = increasingMeth.get(station.lineCode());
        if (up == null) {
            throw RegionalSourceException.failed("daegu export direction unresolved for " + station.lineCode());
        }
        List<RegionalDeparture> out = new ArrayList<>();
        for (Row row : rows) {
            RegionalDayType dayType = switch (row.type()) {
                case "WEEKDAY" -> RegionalDayType.WD;
                case "SAT" -> RegionalDayType.SA;
                case "HOLIDAY" -> RegionalDayType.HO;
                default -> null;
            };
            if (dayType == null) {
                continue;
            }
            String defaultTerminal = row.meth().equals(up) ? last : first;
            for (String item : row.schedule()) {
                OptionalInt seconds = RegionalTimetableText.serviceSeconds(item);
                if (seconds.isEmpty()) {
                    continue;   // "-" = 이 방향 열차 없음
                }
                String terminal = RegionalTimetableText.parenthesized(item);
                out.add(new RegionalDeparture(dayType, seconds.getAsInt(), terminal != null ? terminal : defaultTerminal));
            }
        }
        return out;
    }

    /** 노선 첫 역에서 열차가 있는 방향 = seq 가 커지는 방향. 마지막 역은 반대 방향만 있어야 한다. */
    private String resolveIncreasingMeth(String lineCode) {
        List<RegionalStationSeed> line = catalog.line(lineCode);
        if (line.isEmpty()) {
            throw new IllegalStateException("no seed for " + lineCode);
        }
        String atFirst = onlyNonEmptyMeth(line.get(0));
        String atLast = onlyNonEmptyMeth(line.get(line.size() - 1));
        if (atFirst == null || atLast == null || atFirst.equals(atLast)) {
            throw new IllegalStateException("daegu export direction unresolved for " + lineCode
                    + " first=" + atFirst + " last=" + atLast);
        }
        return atFirst;
    }

    private String onlyNonEmptyMeth(RegionalStationSeed station) {
        List<Row> rows = rowsByStation.getOrDefault(station.stationKey(), List.of());
        Set<String> meths = new java.util.TreeSet<>();
        for (Row row : rows) {
            boolean hasTrain = row.schedule().stream().anyMatch(s -> RegionalTimetableText.serviceSeconds(s).isPresent());
            if (hasTrain) {
                meths.add(row.meth());
            }
        }
        return meths.size() == 1 ? meths.iterator().next() : null;
    }

    private static LocalDate parseDate(String fetchedAt) {
        if (fetchedAt == null || fetchedAt.isBlank()) {
            return null;
        }
        try {
            return java.time.OffsetDateTime.parse(fetchedAt).atZoneSameInstant(ZoneId.of("Asia/Seoul")).toLocalDate();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static JsonNode read(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return objectMapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("daegu timetable read failed", e);
        }
    }

    record Row(String meth, String type, List<String> schedule) {
    }
}
