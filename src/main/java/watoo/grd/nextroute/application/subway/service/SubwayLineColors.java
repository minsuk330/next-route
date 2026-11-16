package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 지하철 노선 표시색. 운영사·지자체 공식 자료에서 모은 표({@code resources/subway/line-colors.json})가 원천이고,
 * 검색 lane·여정 지도·근처 역·역 도착·시간표·환승 노선 등 지하철이 나오는 응답이 모두 이 값을 쓴다(OTP 그래프 route_color 대신).
 *
 * <p>키는 OTP 노선 이름({@code 부산2호선}, {@code 서울2호선}, {@code 신분당선}), 서울 실시간 id({@code 1002}),
 * 지방 노선 코드({@code BS2}), 표의 별칭({@code 2호선}, {@code 김포골드라인}). 공백은 무시한다.
 * 표 밖 노선(KTX·ITX 등 일반 철도)은 빈 값 — 부르는 쪽이 원래 색을 쓴다.
 *
 * <p>표를 못 읽으면 빈 표로 두고 WARN 만 남긴다(부팅을 막지 않는다). 그때는 모든 조회가 빈 값이라 OTP 색으로 돌아간다.
 */
@Slf4j
public final class SubwayLineColors {

    static final String RESOURCE = "/subway/line-colors.json";
    private static final Pattern HEX = Pattern.compile("^#[0-9A-F]{6}$");
    private static final Map<String, String> BY_KEY = load();

    private SubwayLineColors() {
    }

    /** OTP 노선 이름·표시 이름·별칭·지방 노선 코드로 찾는다. */
    public static Optional<String> byLineName(String name) {
        return Optional.ofNullable(name == null ? null : BY_KEY.get(normalize(name)));
    }

    /** 서울 실시간 id({@code 1002}, {@code 1077})로 찾는다. */
    public static Optional<String> byLineId(String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return Optional.empty();
        }
        Optional<String> direct = byLineName(lineId);
        if (direct.isPresent()) {
            return direct;
        }
        return SubwayLineIdMapper.toLineName(lineId).flatMap(SubwayLineColors::byLineName);
    }

    /** id 먼저, 없으면 이름. 둘 다 모르면 null(가짜색 금지). */
    public static String of(String lineId, String lineName) {
        return byLineId(lineId).or(() -> byLineName(lineName)).orElse(null);
    }

    static Map<String, String> load() {
        try (InputStream in = SubwayLineColors.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                log.warn("[SubwayLineColors] {} 없음 — 지하철 색은 OTP 값으로", RESOURCE);
                return Map.of();
            }
            return parse(new ObjectMapper().readTree(in));
        } catch (Exception e) {
            log.warn("[SubwayLineColors] {} 읽기 실패 — 지하철 색은 OTP 값으로: {}", RESOURCE, e.toString());
            return Map.of();
        }
    }

    static Map<String, String> parse(JsonNode root) {
        Map<String, String> byKey = new HashMap<>();
        for (JsonNode line : root.path("lines")) {
            String name = line.path("name").asText("");
            String hex = line.path("hex").asText("").toUpperCase();
            if (name.isBlank() || !HEX.matcher(hex).matches()) {
                log.warn("[SubwayLineColors] 건너뜀 name={} hex={}", name, hex);
                continue;
            }
            put(byKey, name, hex);
            for (JsonNode alias : line.path("aliases")) {
                put(byKey, alias.asText(""), hex);
            }
        }
        return Map.copyOf(byKey);
    }

    private static void put(Map<String, String> byKey, String key, String hex) {
        String k = normalize(key);
        if (k.isEmpty()) {
            return;
        }
        String previous = byKey.putIfAbsent(k, hex);
        if (previous != null && !previous.equals(hex)) {
            log.warn("[SubwayLineColors] 키 {} 가 두 색({}, {})에 겹침 — 먼저 것 사용", key, previous, hex);
        }
    }

    private static String normalize(String s) {
        return s.replaceAll("\\s+", "");
    }
}
