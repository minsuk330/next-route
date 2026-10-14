package watoo.grd.nextroute.application.subway.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 지하철 내리는 문 방향(#335). 하차역 승강장 형식과 노선 통행 방향으로 정한다 — 열차가 오른쪽으로 다니면 섬식(가운데
 * 승강장)은 열차 왼쪽, 상대식(바깥 승강장 둘)은 오른쪽이다. 왼쪽으로 다니면 반대다.
 *
 * <p>형식 원천은 KRIC 역사별 승강장 정보({@code subway/platform-type.csv}, {@code tools/subway-platform-type} 로 생성).
 * 문 좌우를 직접 주는 공공 데이터는 없다.
 *
 * <p>통행 방향은 <b>출처를 확인한 노선과 같은 열차가 이어 달리는 연장 구간만</b> {@link #SECTIONS} 에 넣는다(2026-10-12
 * 사용자 결정). 틀리면 문을 정반대로 안내하므로 모르는 노선은 비워 둔다. 복합 승강장(1섬 1상대식·쌍섬식 등)은
 * 방향마다 다를 수 있어 값을 내지 않는다.
 */
@Slf4j
@Component
public class SubwayDoorSideIndex {

    private static final String RESOURCE = "subway/platform-type.csv";

    public enum Side { LEFT, RIGHT }

    public enum PlatformType { ISLAND, SIDE, OTHER }

    /** @param station 역 이름(적재 때 정규화한다) */
    public record Row(String opr, String lnCd, String station, PlatformType type) {
    }

    /** 내부 line_id 의 KRIC 구간 하나(운영기관·노선 코드). */
    record Section(String opr, String lnCd, boolean leftHand) {
    }

    private static Section right(String opr, String lnCd) {
        return new Section(opr, lnCd, false);
    }

    private static Section left(String opr, String lnCd) {
        return new Section(opr, lnCd, true);
    }

    /**
     * 내부 line_id → KRIC 구간. 한국 일반철도는 좌측, 서울 지하철(도시철도)은 우측 통행이고, 두 방식이 직결되는 곳은
     * 운영기관 경계에서 바뀐다(4호선 남태령~선바위 꽈배기 굴). 표에 없는 노선(GTX-A·공항·경춘·신분당·경강·우이신설·신림)은
     * 출처 미확인이라 값을 내지 않는다.
     */
    static final Map<String, List<Section>> SECTIONS = Map.ofEntries(
            // 1호선은 서울역~청량리 지하 구간도 일반철도 직결이라 좌측
            Map.entry("1001", List.of(left("S1", "1"), left("KR", "1"))),
            Map.entry("1002", List.of(right("S1", "2"))),
            // 일산선(코레일)은 3호선 직결로 서울 방식(우측)
            Map.entry("1003", List.of(right("S1", "3"), right("KR", "3"))),
            // 과천·안산선(코레일)은 좌측, 진접선(남양주)은 당고개 연장
            Map.entry("1004", List.of(right("S1", "4"), left("KR", "4"), right("NU", "4"))),
            Map.entry("1005", List.of(right("S1", "5"))),
            Map.entry("1006", List.of(right("S1", "6"))),
            // 인천 구간은 같은 열차가 이어 달리는 연장
            Map.entry("1007", List.of(right("S1", "7"), right("IC", "7"))),
            // 별내선(구리·남양주)은 연장
            Map.entry("1008", List.of(right("S1", "8"), right("GU", "8"), right("NU", "8"))),
            Map.entry("1009", List.of(right("S9", "9"))),
            Map.entry("1063", List.of(left("KR", "K4"))),
            // 분당선 좌측 확인, 수인선은 직결
            Map.entry("1075", List.of(left("KR", "K1"))),
            Map.entry("1093", List.of(left("SW", "WS"), left("KR", "WS")))
    );

    private volatile List<Row> rows;

    public SubwayDoorSideIndex() {
    }

    /** 이름은 정규화해 넣는다(번들 CSV 적재와 같게). */
    public static SubwayDoorSideIndex of(List<Row> rows) {
        SubwayDoorSideIndex index = new SubwayDoorSideIndex();
        index.rows = rows.stream()
                .map(row -> new Row(row.opr(), row.lnCd(), norm(row.station()), row.type()))
                .toList();
        return index;
    }

    /**
     * @param lineId        내부 line_id({@code 1002})
     * @param alightStation 하차역 이름(정규화 전도 된다)
     * @return 그 노선 구간들에서 이름이 같은 역이 정확히 하나이고 섬식·상대식일 때만 값
     */
    public Optional<Side> lookup(String lineId, String alightStation) {
        List<Section> sections = lineId == null ? null : SECTIONS.get(lineId);
        String station = norm(alightStation);
        if (sections == null || station == null) {
            return Optional.empty();
        }
        Section matchedSection = null;
        Row matched = null;
        int hits = 0;
        for (Row row : all()) {
            if (!station.equals(row.station())) {
                continue;
            }
            for (Section section : sections) {
                if (section.opr().equals(row.opr()) && section.lnCd().equals(row.lnCd())) {
                    hits++;
                    matched = row;
                    matchedSection = section;
                }
            }
        }
        if (hits != 1 || matched.type() == PlatformType.OTHER) {
            return Optional.empty();
        }
        boolean island = matched.type() == PlatformType.ISLAND;
        // 우측 통행: 섬식=왼쪽, 상대식=오른쪽. 좌측 통행은 반대
        return Optional.of(island != matchedSection.leftHand() ? Side.LEFT : Side.RIGHT);
    }

    private static String norm(String name) {
        return name == null ? null : SubwayPatternStationMatcher.displayName(name);
    }

    List<Row> all() {
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
                if (f.length < 5) {
                    continue;
                }
                PlatformType type;
                try {
                    type = PlatformType.valueOf(f[4].trim());
                } catch (IllegalArgumentException e) {
                    continue;
                }
                String station = norm(f[3]);
                if (station != null) {
                    out.add(new Row(f[0].trim(), f[1].trim(), station, type));
                }
            }
        } catch (Exception e) {
            log.warn("[Subway] 승강장 형식 목록 적재 실패 — 내리는 문 없이 진행: {}", e.getMessage());
            return List.of();
        }
        log.info("[Subway] 승강장 형식 목록 적재 rows={}", out.size());
        return List.copyOf(out);
    }
}
