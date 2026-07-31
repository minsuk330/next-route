package watoo.grd.nextroute.application.journey.service;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 검색 결과의 지하철 노선 표기(예: {@code 2호선})를 서비스 내부 {@code line_id}로 바꾼다.
 *
 * <p>ODSAY의 {@code subwayCode}는 내부 정적 데이터의 식별자라는 보장이 없으므로, 사람이 보는
 * 노선명으로 먼저 해석한다. 이 값은 서울 실시간 도착 정보와 같은 {@code line_id} 체계를 쓴다.
 */
@Component
public class SubwayLineIdResolver {

    private static final Map<String, String> NAMED_LINES = Map.ofEntries(
            Map.entry("경의중앙선", "1063"),
            Map.entry("공항철도", "1065"),
            Map.entry("경춘선", "1067"),
            Map.entry("수인분당선", "1075"),
            Map.entry("신분당선", "1077"),
            Map.entry("경강선", "1081"),
            Map.entry("우이신설선", "1092"),
            Map.entry("서해선", "1093"),
            Map.entry("GTX-A", "1032"),
            Map.entry("중앙선", "1061")
    );

    public Optional<String> resolve(String routeName, String sourceRouteId) {
        String normalizedName = normalize(routeName);
        if (!normalizedName.isEmpty()) {
            String named = NAMED_LINES.get(normalizedName);
            if (named != null) {
                return Optional.of(named);
            }
            String numeric = numericLineId(normalizedName);
            if (numeric != null) {
                return Optional.of(numeric);
            }
        }

        // 이름이 비어 있을 때만 원천 코드를 보조 근거로 쓴다. 원천 코드를 내부 ID로 저장하지는 않는다.
        String numeric = numericLineId(sourceRouteId);
        return Optional.ofNullable(numeric);
    }

    private String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private String numericLineId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            int line = Integer.parseInt(digits);
            return line > 0 && line < 100 ? String.valueOf(1000 + line) : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
