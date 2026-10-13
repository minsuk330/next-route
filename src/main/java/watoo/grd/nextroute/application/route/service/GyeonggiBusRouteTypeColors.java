package watoo.grd.nextroute.application.route.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 경기 버스 노선 유형별 표시색(#391). 그래프 빌드의 노선 유형 색은 서울 버스에만 있어 경기 버스가 전부 기본 초록으로
 * 내려온다 — 직행좌석 4403 이 초록. 그래프를 다시 빌드하지 않고 응답에서 노선 유형으로 색을 정한다.
 *
 * <p>노선 유형 원천은 GBIS 노선 조회({@code bus/gyeonggi-route-types.csv}, {@code tools/gyeonggi-bus-route-type} 로
 * 생성). 색은 앱이 이미 쓰는 서울 노선 색과 같은 값만 쓴다(2026-10-12 사용자 결정): 직행좌석·광역급행·경기순환·공항 =
 * 광역 빨강, 좌석 = 간선 파랑, 일반 = 지선 초록, 마을 = 순환 노랑, 시외 = 시외 보라.
 */
@Slf4j
@Component
public class GyeonggiBusRouteTypeColors {

    private static final String RESOURCE = "bus/gyeonggi-route-types.csv";

    /** OTP 노선 gtfsId. 경기 마을버스는 노선만 {@code BR_3100_} 기관 코드로 온다({@code OtpSeoulIdentityResolver} 와 같은 규칙). */
    private static final Pattern GG_ROUTE = Pattern.compile("^(?:\\d+:)?BR_(?:TAGO_GGB|3100_)(\\d{9})(?:_[UD])?$");

    private static final String RED = "#E60012";
    private static final String BLUE = "#0068B7";
    private static final String GREEN = "#53B332";
    private static final String YELLOW = "#F2B70A";
    private static final String PURPLE = "#8652A1";

    /** GBIS routeTypeCd → 색. 표에 없는 유형(따복 15, 수요응답 50 등)은 원천 색을 그대로 둔다. */
    static final Map<Integer, String> TYPE_COLORS = Map.ofEntries(
            Map.entry(11, RED),     // 직행좌석형시내
            Map.entry(14, RED),     // 광역급행형시내(M)
            Map.entry(16, RED),     // 경기순환
            Map.entry(21, RED),     // 직행좌석형농어촌
            Map.entry(51, RED),     // 리무진형 공항
            Map.entry(52, RED),     // 좌석형 공항
            Map.entry(53, RED),     // 일반형 공항
            Map.entry(12, BLUE),    // 좌석형시내
            Map.entry(22, BLUE),    // 좌석형농어촌
            Map.entry(13, GREEN),   // 일반형시내
            Map.entry(23, GREEN),   // 일반형농어촌
            Map.entry(30, YELLOW),  // 마을
            Map.entry(41, PURPLE),  // 고속형시외
            Map.entry(42, PURPLE),  // 좌석형시외
            Map.entry(43, PURPLE)   // 일반형시외
    );

    private volatile Map<String, Integer> types;

    public GyeonggiBusRouteTypeColors() {
    }

    public static GyeonggiBusRouteTypeColors of(Map<String, Integer> typesByRouteId) {
        GyeonggiBusRouteTypeColors colors = new GyeonggiBusRouteTypeColors();
        colors.types = Map.copyOf(typesByRouteId);
        return colors;
    }

    /**
     * @param sourceRouteId OTP 노선 gtfsId({@code 1:BR_TAGO_GGB233000265})
     * @return 경기 노선이고 유형을 알면 {@code #RRGGBB}
     */
    public Optional<String> color(String sourceRouteId) {
        if (sourceRouteId == null) {
            return Optional.empty();
        }
        Matcher m = GG_ROUTE.matcher(sourceRouteId);
        if (!m.matches()) {
            return Optional.empty();
        }
        Integer type = all().get(m.group(1));
        return type == null ? Optional.empty() : Optional.ofNullable(TYPE_COLORS.get(type));
    }

    private Map<String, Integer> all() {
        Map<String, Integer> loaded = types;
        if (loaded == null) {
            synchronized (this) {
                if (types == null) {
                    types = load();
                }
                loaded = types;
            }
        }
        return loaded;
    }

    private static Map<String, Integer> load() {
        Map<String, Integer> out = new HashMap<>();
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
                // route_id,route_name,route_type_cd — 이름에 무엇이 들어 있든 처음·마지막 칸만 읽는다
                int first = line.indexOf(',');
                int last = line.lastIndexOf(',');
                if (first <= 0 || last <= first) {
                    continue;
                }
                try {
                    out.put(line.substring(0, first).trim(), Integer.valueOf(line.substring(last + 1).trim()));
                } catch (NumberFormatException e) {
                    // 유형 없는 행은 건너뛴다
                }
            }
        } catch (Exception e) {
            log.warn("[Route] 경기 노선 유형 목록 적재 실패 — 원천 색으로 진행: {}", e.getMessage());
            return Map.of();
        }
        log.info("[Route] 경기 노선 유형 목록 적재 rows={}", out.size());
        return Map.copyOf(out);
    }
}
