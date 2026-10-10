package watoo.grd.nextroute.application.subway.service;

import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
            Map.entry("GTX-A", "1032")
    );

    /**
     * 숫자로 읽어도 되는 노선 이름: {@code 2호선}·{@code 서울2호선}·{@code 수도권2호선}. 그 밖에 숫자가 든 이름
     * ({@code 부산1호선}·{@code 인천2호선}·{@code [일반]1경부서울부산})은 서울 노선이 아니다.
     */
    private static final Pattern CAPITAL_NUMBERED = Pattern.compile("^(?:서울|수도권)?(\\d{1,2})호선$");

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
     *
     * <p>이름이 있는데 모르는 노선이면 비어 있다 — 이름 속 숫자만 뽑으면 OTP 의 {@code 부산1호선}·{@code 인천2호선}·
     * 철도 {@code [일반]1경부서울부산} 이 서울 1·2호선이 되어 서울 열차·역이 붙는다. OTP 이름은
     * {@link SubwayRoutePatternAssembler#LINE_IDS} 명시표로 읽는다(신림선·우이신설경전철 포함).
     */
    public static Optional<String> resolveOdsay(String routeName, String sourceRouteId) {
        String normalizedName = normalize(routeName);
        if (!normalizedName.isEmpty()) {
            String named = NAMED_LINES.get(normalizedName);
            if (named == null) {
                named = SubwayRoutePatternAssembler.LINE_IDS.get(normalizedName);
            }
            if (named != null) {
                return Optional.of(named);
            }
            Matcher numbered = CAPITAL_NUMBERED.matcher(normalizedName);
            if (numbered.matches()) {
                return Optional.ofNullable(numericLine(numbered.group(1))).map(n -> String.valueOf(1000 + n));
            }
            return Optional.empty();
        }

        Integer numeric = numericLine(sourceRouteId);
        return numeric == null
                ? Optional.empty()
                : Optional.of(String.valueOf(1000 + numeric));
    }

    /**
     * 내부 line_id 를 서울 API 의 호선명으로 되돌린다.
     *
     * <p>실시간 <b>위치</b> API 는 경로에 line_id 가 아니라 호선명을 받는다
     * ({@code /realtimePosition/0/1000/2호선}). 그래서 이 매핑이 필요하다.
     *
     * <p>모르는 line_id 는 <b>비어 있는 값</b>을 돌려준다. 숫자를 그럴듯하게 지어내 호출하면
     * 엉뚱한 노선의 열차를 우리 노선인 줄 알고 추적한다 — 안 부르는 편이 낫다.
     */
    public static Optional<String> toLineName(String lineId) {
        if (lineId == null || lineId.isBlank()) {
            return Optional.empty();
        }
        String value = lineId.trim();
        for (Map.Entry<String, String> entry : NAMED_LINES.entrySet()) {
            if (entry.getValue().equals(value)) {
                return Optional.of(entry.getKey());
            }
        }
        try {
            int numeric = Integer.parseInt(value) - 1000;
            return numeric > 0 && numeric < 100
                    ? Optional.of(numeric + "호선")
                    : Optional.empty();
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
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
