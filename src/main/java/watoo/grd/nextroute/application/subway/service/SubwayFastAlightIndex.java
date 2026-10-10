package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 빠른 하차 칸·문(#266 PR-4). 원천은 서울교통공사_빠른하차정보({@code subway/fast-alight.csv},
 * {@code tools/subway-fast-alight} 로 생성) — <b>1~8호선 서울교통공사 구간만</b>.
 *
 * <p>원천 한 행 = (호선, 하차역, 진행 방향 다음 역 {@code drtnInfo}, 칸-문, 그 문 앞 이동 시설). 방향 표기는 2호선도
 * 상행/하행이라 우리 내선/외선과 못 맞춘다. 그래서 <b>하차 직전 역이 아닌 쪽</b>을 고른다 — 그 역에서 계속 갔을 때의
 * 다음 역이 {@code drtnInfo} 이므로, 지나온 역(하차 직전 역)을 가리키는 행은 반대 방향이다. 역에 한 방향 행만 있으면
 * (종착) 그것을 쓴다.
 */
@Slf4j
@Component
public class SubwayFastAlightIndex {

    private static final String RESOURCE = "subway/fast-alight.csv";
    static final int LIMIT = 3;

    /** 시설 순서 = 보여 줄 순서. 계단이 가장 흔하고 빠르다. */
    private static final Map<String, String> FACILITY = Map.of(
            "계단", "STAIRS", "에스컬레이터", "ESCALATOR", "엘리베이터", "ELEVATOR");
    private static final List<String> FACILITY_ORDER = List.of("STAIRS", "ESCALATOR", "ELEVATOR");

    public record Row(int line, String station, String nextStation, String carDoor, String facility) {
    }

    public record FastAlight(String carDoor, String facility) {
    }

    private volatile List<Row> rows;

    public SubwayFastAlightIndex() {
    }

    public static SubwayFastAlightIndex of(List<Row> rows) {
        SubwayFastAlightIndex index = new SubwayFastAlightIndex();
        index.rows = List.copyOf(rows);
        return index;
    }

    /**
     * @param line              호선 번호(1~8). 그 밖은 빈 목록
     * @param alightStation     하차역 이름(정규화 전도 된다)
     * @param prevStation       하차 직전 역 이름. 모르면 방향을 못 정해 빈 목록(한 방향뿐인 역 제외)
     * @return 시설 순(계단 → 에스컬레이터 → 엘리베이터), 칸-문 중복 제거, 최대 {@value #LIMIT}개
     */
    public List<FastAlight> lookup(int line, String alightStation, String prevStation) {
        String station = norm(alightStation);
        if (station == null || line < 1 || line > 8) {
            return List.of();
        }
        List<Row> atStation = all().stream()
                .filter(r -> r.line() == line && station.equals(r.station()))
                .toList();
        Set<String> directions = new java.util.HashSet<>();
        atStation.forEach(r -> directions.add(r.nextStation()));
        String prev = norm(prevStation);
        List<Row> chosen;
        if (directions.size() == 1) {
            chosen = atStation;
        } else if (prev != null && directions.contains(prev)) {
            chosen = atStation.stream().filter(r -> !prev.equals(r.nextStation())).toList();
            if (chosen.stream().map(Row::nextStation).distinct().count() != 1) {
                return List.of(); // 갈래역: 하차 직전 역 말고도 방향이 둘 이상
            }
        } else {
            return List.of();
        }
        Map<String, FastAlight> byDoor = new LinkedHashMap<>();
        chosen.stream()
                .sorted(Comparator.comparingInt(r -> FACILITY_ORDER.indexOf(r.facility())))
                .forEach(r -> byDoor.putIfAbsent(r.carDoor(), new FastAlight(r.carDoor(), r.facility())));
        return byDoor.values().stream().limit(LIMIT).toList();
    }

    private static String norm(String name) {
        return name == null ? null : SubwayPatternStationMatcher.displayName(name);
    }

    private List<Row> all() {
        List<Row> loaded = rows;
        if (loaded == null) {
            synchronized (this) {
                if (rows == null) {
                    rows = load();
                }
                loaded = rows;
            }
        }
        return loaded;
    }

    private static List<Row> load() {
        List<Row> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource(RESOURCE).getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            boolean header = true;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("#") || line.isBlank()) {
                    continue;
                }
                if (header) {
                    header = false;
                    continue;
                }
                String[] f = line.split(",", -1);
                if (f.length < 6) {
                    continue;
                }
                String facility = FACILITY.get(f[5].trim());
                Integer lineNo = lineNo(f[0]);
                if (facility == null || lineNo == null) {
                    continue;
                }
                out.add(new Row(lineNo, norm(f[1]), norm(f[3]), f[4].trim(), facility));
            }
        } catch (Exception e) {
            log.warn("[Subway] 빠른 하차 목록 적재 실패 — 없이 진행: {}", e.getMessage());
            return List.of();
        }
        log.info("[Subway] 빠른 하차 목록 적재 rows={}", out.size());
        return List.copyOf(out);
    }

    /** "2호선" → 2. */
    static Integer lineNo(String lineName) {
        if (lineName == null) {
            return null;
        }
        String digits = lineName.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : Integer.valueOf(digits);
    }

    /** 내부 line_id({@code 1002}) → 호선 번호. 1~8호선(1001~1008)만. */
    public static Integer lineNoOfLineId(String lineId) {
        if (lineId == null || !lineId.matches("100[1-8]")) {
            return null;
        }
        return lineId.charAt(3) - '0';
    }
}
