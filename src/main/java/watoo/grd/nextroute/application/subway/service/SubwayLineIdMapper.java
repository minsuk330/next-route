package watoo.grd.nextroute.application.subway.service;

import java.util.Map;
import java.util.Optional;

/**
 * 외부 지하철 노선 표기를 서버 내부 {@code line_id}로 변환하는 단일 매핑 소스.
 *
 * <p>서울시 정적 데이터 수집과 ODSAY 경로 해석이 같은 코드를 써야 두 데이터의 노선 ID가
 * 어긋나지 않는다.
 */
public final class SubwayLineIdMapper {

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

    private SubwayLineIdMapper() {
    }

    /** 서울시 노선 표기를 내부 line_id로 바꾼다. 알 수 없는 이름은 기존 동작대로 원문을 반환한다. */
    public static String toInternalLineId(String route) {
        if (route == null) {
            return null;
        }
        String normalized = normalize(route);
        String named = NAMED_LINES.get(normalized);
        if (named != null) {
            return named;
        }
        Integer numeric = numericLine(normalized);
        return numeric == null ? route : String.valueOf(1000 + numeric);
    }

    /**
     * ODSAY 노선 후보를 내부 line_id로 안전하게 해석한다.
     *
     * <p>노선명을 우선하고, 이름이 없을 때만 1~99 범위의 subwayCode를 보조 근거로 쓴다.
     * 큰 ODSAY ID를 내부 line_id로 오인하지 않는다.
     */
    public static Optional<String> resolveOdsay(String routeName, String sourceRouteId) {
        String normalizedName = normalize(routeName);
        if (!normalizedName.isEmpty()) {
            String named = NAMED_LINES.get(normalizedName);
            if (named != null) {
                return Optional.of(named);
            }
            Integer numeric = numericLine(normalizedName);
            if (numeric != null) {
                return Optional.of(String.valueOf(1000 + numeric));
            }
        }

        Integer numeric = numericLine(sourceRouteId);
        return numeric == null
                ? Optional.empty()
                : Optional.of(String.valueOf(1000 + numeric));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replaceAll("\\s+", "").trim();
    }

    private static Integer numericLine(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        try {
            int line = Integer.parseInt(digits);
            return line > 0 && line < 100 ? line : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
