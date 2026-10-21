package watoo.grd.nextroute.application.subway.service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 급행 카탈로그와 실시간 열차를 맞대는 번호·방향·행선지 정규화 계약(subway-express PR-S1).
 *
 * <h2>번호는 두 가지다</h2>
 * <ul>
 *   <li><b>저장 identity</b>({@link #trainNo}) — 실시간 원문 그대로. 위치·출발 기록과 링 조회
 *       ({@code SubwayRingSignalReader}) 는 원문 완전 일치로 찾으므로 여기서 바꾸면 안 된다.</li>
 *   <li><b>카탈로그 비교용</b>({@link #catalogNo}) — 원천마다 모양이 달라 맞춘다. 실측(2026-10-21):
 *       서울 시간표 API 는 {@code K1056}, 실시간 도착·위치 API 는 {@code 1056}, 1호선·GTX-A 는 선행 0 이 붙은
 *       {@code 0112}, 공항철도는 {@code A1031}. 그래서 앞 {@code K} 와 선행 0 을 떼고, 공항철도 {@code A} 는 남긴다
 *       (직통 판정 {@code A1001–A1056} 이 접두로 노선을 가른다).</li>
 * </ul>
 *
 * <h2>방향</h2>
 * <p>도착 API 표기(상행·하행·내선·외선)를 쓴다. 위치 API 의 {@code updnLine} 숫자는 {@link SubwayDirectionNormalizer} 로만
 * 바꾼다 — 9호선·공항철도는 위치 API 방향이 반대라 거기서 뒤집는다. 같은 매핑을 여기 복제하지 않는다. 이미 라벨인 값은
 * 그 함수에 넣지 않는다({@link #direction} 참고). 그래서 9호선
 * 김포공항 → 중앙보훈병원은 <b>상행</b>이다(도착 API 실측: 하행 행선지가 개화·김포공항).
 *
 * <h2>행선지</h2>
 * <p>역 마스터 정규화명({@link DestinationNormalizer}: 공백·괄호 접미사·끝 "행"/"역" 제거) + 개명 별칭. 위치 기반
 * 출발 기록은 이름 없이 종착 역 id({@code terminalId})만 있다({@code SubwayDepartureLookup}) — 10자리 역 id 면 id 로 받는다.
 *
 * @param trainNo         실시간 원문 번호(저장 identity). 없으면 null
 * @param catalogNo       카탈로그 비교용 번호. 없으면 null
 * @param direction       도착 API 표기. 모르면 null
 * @param destinationName 정규화·별칭 적용한 행선지 이름. 모르거나 역 id 로 받았으면 null
 * @param terminalId      종착 역 id. 이름으로 받았으면 null
 */
public record SubwayTrainIdentity(String lineId, String direction, String trainNo, String catalogNo,
                                  String destinationName, String terminalId) {

    private static final DestinationNormalizer NORMALIZER = new DestinationNormalizer();

    /** 서울 역 id(line_id 4자리 + 6자리). 실시간 terminalId 가 이 모양이다. */
    private static final Pattern STATION_ID = Pattern.compile("\\d{10}");

    private static final Pattern KORAIL = Pattern.compile("K(\\d+)");
    private static final Pattern AIRPORT = Pattern.compile("A(\\d+)");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    /**
     * 행선지 별칭(정규화 뒤 이름 → 역 마스터 정규화명). 개명·원천별 표기 차이만 둔다. {@code 서울역}·{@code 신촌(경의중앙선)}·
     * {@code 쌍용(나사렛대)} 처럼 정규화만으로 같아지는 것은 넣지 않는다.
     */
    static final Map<String, String> DESTINATION_ALIASES = Map.of(
            "당고개", "불암산",       // 2024-10-31 개명
            "화전", "한국항공대",     // 2023-11-21 개명
            "평택지제", "지제",       // OTP·시간표 표기 vs 역 마스터
            "이수", "총신대입구");    // 4호선 병기명

    /** @param destination 행선지 이름이나 종착 역 id(10자리) */
    public static SubwayTrainIdentity of(String lineId, String direction, String trainNo, String destination) {
        boolean isId = destination != null && STATION_ID.matcher(destination.trim()).matches();
        return of(lineId, direction, trainNo, isId ? null : destination, isId ? destination.trim() : null);
    }

    public static SubwayTrainIdentity of(String lineId, String direction, String trainNo, String destinationName,
                                         String terminalId) {
        String raw = trainNo == null || trainNo.isBlank() ? null : trainNo.trim();
        String terminal = terminalId == null || terminalId.isBlank() ? null : terminalId.trim();
        return new SubwayTrainIdentity(lineId, direction(lineId, direction), raw, catalogNumber(raw),
                destinationName(destinationName), terminal);
    }

    /**
     * 카탈로그 비교용 번호. {@code K1056}→{@code 1056}, {@code 0112}→{@code 112}, {@code K0112}→{@code 112},
     * {@code A1031}→{@code A1031}, {@code A01031}→{@code A1031}. 그 밖 모양은 대문자로만 바꿔 그대로 둔다(지어내지 않는다).
     */
    public static String catalogNumber(String trainNo) {
        if (trainNo == null || trainNo.isBlank()) {
            return null;
        }
        String value = trainNo.trim().toUpperCase();
        Matcher korail = KORAIL.matcher(value);
        if (korail.matches()) {
            return stripZeros(korail.group(1));
        }
        Matcher airport = AIRPORT.matcher(value);
        if (airport.matches()) {
            return "A" + stripZeros(airport.group(1));
        }
        return DIGITS.matcher(value).matches() ? stripZeros(value) : value;
    }

    /** 숫자만인 카탈로그 번호의 값. 접두가 있거나 없으면 null. */
    public static Integer numeric(String catalogNo) {
        if (catalogNo == null || !DIGITS.matcher(catalogNo).matches() || catalogNo.length() > 9) {
            return null;
        }
        return Integer.valueOf(catalogNo);
    }

    /**
     * 도착 API 방향 표기. 이미 한글 라벨이면 <b>그대로</b>, 위치 API {@code updnLine}({@code 0}/{@code 1})이면
     * {@link SubwayDirectionNormalizer#fromUpDownLine} 로(9호선·공항철도 반전 포함). 모르면 null.
     *
     * <p>라벨을 {@code fromUpDownLine} 에 넣으면 안 된다 — 그 함수는 한글 라벨도 9호선·공항철도에서 뒤집는다(라벨을
     * 위치 API 의미로 읽는다). 도착 API·저장 identity 의 라벨을 넣으면 반대 방향이 된다.
     */
    public static String direction(String lineId, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        if ("상행".equals(value) || "하행".equals(value) || "내선".equals(value) || "외선".equals(value)) {
            return value;
        }
        if (lineId == null || !("0".equals(value) || "1".equals(value))) {
            return null;
        }
        return SubwayDirectionNormalizer.fromUpDownLine(lineId, value);
    }

    /** 반대 방향(상행↔하행, 내선↔외선). 모르면 null. */
    public static String opposite(String direction) {
        if (direction == null) {
            return null;
        }
        return switch (direction) {
            case "상행" -> "하행";
            case "하행" -> "상행";
            case "내선" -> "외선";
            case "외선" -> "내선";
            default -> null;
        };
    }

    /** 행선지 이름 정규화 + 별칭. 비면 null. */
    public static String destinationName(String name) {
        String normalized = NORMALIZER.normalize(name);
        return normalized == null ? null : DESTINATION_ALIASES.getOrDefault(normalized, normalized);
    }

    private static String stripZeros(String digits) {
        String stripped = digits.replaceFirst("^0+", "");
        return stripped.isEmpty() ? "0" : stripped;
    }
}
