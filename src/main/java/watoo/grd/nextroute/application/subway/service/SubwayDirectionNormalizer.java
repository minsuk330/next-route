package watoo.grd.nextroute.application.subway.service;

import java.util.Set;

/**
 * 방향 코드를 도착정보와 같은 한글 표기로 맞춘다. <b>원천마다 값 규칙이 다르므로 입구를 나눈다.</b>
 *
 * <pre>
 * ODsay wayCode  : 1 → 상행,  2 → 하행           (2호선은 0 → 내선, 1 → 외선)
 * 서울 updnLine  : 0 → 상행,  1 → 하행           (2호선은 0 → 내선, 1 → 외선)
 * </pre>
 *
 * <p>숫자와 뜻의 대응이 서로 다르다. 한 함수에 합치려는 순간 둘 중 하나가 틀리고, 반대 방향
 * 열차를 고르면 ETA 도 탑승 판정도 <b>그럴듯하게</b> 틀린다.
 *
 * <p><b>둘 다 모르는 값에는 {@code null} 을 준다.</b> 한쪽만 관대하면 값이 깨졌을 때 그쪽에서만
 * 방향이 지어져 나온다. 모름은 위로 올라가 "판정하지 않음"이 되지만, 지어낸 방향은 조용히
 * 반대 방향 열차에 고정된다.
 *
 * <p><b>실측(2026-10-10 운영)</b>: 같은 열차번호의 도착 API 방향과 위치 API 방향을 노선별로 대조했다. 17개 노선은
 * 위 규칙과 일치했지만 <b>9호선(1009) 26/26, 공항철도(1065) 24/24 는 정반대</b>였다(도착 {@code 하행 → 개화} 인 열차가
 * 위치에선 {@code 0}). 그래서 두 노선은 {@link #INVERTED_UPDN_LINES} 로 뒤집는다. 뒤집지 않으면 역 도착에서 그 노선
 * 열차가 전부 "반대 방향으로 더 최근에 본 열차"로 빠진다(9호선 0/38역). 이 매핑을 다른 곳에 복제하지 않는다 —
 * 고칠 자리가 하나여야 한다.
 */
public final class SubwayDirectionNormalizer {

    /** 2호선. 상·하행이 아니라 내·외선으로 말한다. */
    private static final String CIRCULAR_LINE_ID = "1002";

    /** 위치 API 의 상·하행이 도착 API 와 반대인 노선(실측, 클래스 주석). */
    static final Set<String> INVERTED_UPDN_LINES = Set.of("1009", "1065");

    private SubwayDirectionNormalizer() {
    }

    /**
     * 위치 API 의 {@code updnLine} 을 도착정보 표기로 바꾼다.
     *
     * @param lineId    내부 line_id
     * @param updnLine  {@code "0"} 또는 {@code "1"}. 이미 한글 표기로 오는 경우도 그대로 받는다
     * @return 상행·하행·내선·외선 중 하나. 해석하지 못하면 {@code null}
     */
    public static String fromUpDownLine(String lineId, String updnLine) {
        String label = upDownLineAsSpecified(lineId, updnLine);
        if (label == null || !INVERTED_UPDN_LINES.contains(lineId)) {
            return label;
        }
        return "상행".equals(label) ? "하행" : "하행".equals(label) ? "상행" : label;
    }

    /** 공식 명세 규칙 그대로(뒤집기 전). */
    private static String upDownLineAsSpecified(String lineId, String updnLine) {
        if (updnLine == null) {
            return null;
        }
        String value = updnLine.trim();
        if (value.isEmpty()) {
            return null;
        }
        // 이미 한글 표기로 오는 응답을 만나면 그대로 통과시킨다. 숫자로 바꿔 되돌리는 왕복이
        // 늘어날 뿐이고, 그 왕복에서 매핑을 한 번 더 틀릴 수 있다.
        if (isKnownLabel(value)) {
            return value;
        }
        boolean circular = CIRCULAR_LINE_ID.equals(lineId);
        return switch (value) {
            case "0" -> circular ? "내선" : "상행";
            case "1" -> circular ? "외선" : "하행";
            // 모르는 값에서 방향을 지어내지 않는다. 반대 방향 열차를 고르는 것보다 모름이 낫다.
            default -> null;
        };
    }

    /**
     * ODsay {@code wayCode} 를 실시간 표기로 바꾼다. <b>모르는 값은 {@code null} 이다.</b>
     *
     * <p>{@link #fromUpDownLine} 은 이미 모르는 값에 {@code null} 을 준다. 이쪽만 무엇이든 방향을
     * 만들어 내면, 값이 깨졌을 때 <b>반대 방향 열차에 고정</b>된다. 모름이 그럴듯한 오답보다 낫다.
     *
     * <p><b>허용값이 노선별로 다르다</b> — 2호선은 {@code 0/1}, 그 외는 {@code 1/2} 다.
     * {@code updnLine} 의 값 공간({@code 0/1})과 같은 화이트리스트로 묶으면 하행({@code 2})이
     * 통째로 죽는다.
     *
     * @param lineId  내부 line_id
     * @param wayCode ODsay 방향 코드
     * @return 상행·하행·내선·외선 중 하나. 해석하지 못하면 {@code null}
     */
    public static String fromWayCode(String lineId, Integer wayCode) {
        if (wayCode == null) {
            return null;
        }
        if (CIRCULAR_LINE_ID.equals(lineId)) {
            return switch (wayCode) {
                case 0 -> "내선";
                case 1 -> "외선";
                default -> null;
            };
        }
        return switch (wayCode) {
            case 1 -> "상행";
            case 2 -> "하행";
            default -> null;
        };
    }

    /**
     * 방향 라벨을 ODsay {@code wayCode} 로 되돌린다. <b>모르는 값은 {@code null}</b>.
     *
     * <p>OTP 노선 이름이 방향을 들고 있어서 필요해졌다({@code 서울2호선(본선)<내선>}). 그 라벨을
     * 검색 응답의 {@code wayCode} 칸에 채워야 앱이 지금까지 쓰던 호출을 그대로 쓸 수 있다.
     *
     * <p><b>순환선 여부를 노선 id 로 묻지 않는다 — 라벨이 이미 답이다.</b> 내·외선으로 말하는
     * 노선은 그 말 자체가 순환선이라는 뜻이고, 상·하행으로 말하면 아니다. id 로 가르려 들면
     * "인천2호선"처럼 <b>번호만 같고 순환이 아닌</b> 노선을 2호선(1002)으로 오인한다.
     *
     * <p>{@code 외선}(1)과 {@code 상행}(1)이 같은 숫자인 것은 ODsay 값 공간이 원래 그렇다. 읽는
     * 쪽은 노선을 알고 있어 {@link #fromWayCode} 로 정확히 되돌린다 — 왕복이 성립한다.
     */
    public static Integer toWayCode(String label) {
        if (label == null) {
            return null;
        }
        return switch (label.trim()) {
            case "상행" -> 1;
            case "하행" -> 2;
            case "내선" -> 0;
            case "외선" -> 1;
            default -> null;
        };
    }

    /**
     * 노선 이름에서 방향 라벨을 읽는다. 못 읽으면 {@code null}.
     *
     * <p>KTDB feed 는 {@code direction_id} 를 안 주고(전 노선 -1) {@code longName} 에 방향을
     * 적는다. <b>적는 방식이 두 가지다</b>(실측 61개 노선):
     *
     * <ul>
     *   <li>꺾쇠로 덧붙인다 — {@code 서울2호선(본선)<내선>}, {@code 신림선<상행>}(도시철도 59개)</li>
     *   <li>이름 자리에 방향만 있다 — {@code 상행}(교외선 등 RAIL 2개)</li>
     * </ul>
     *
     * <p>꺾쇠를 먼저 보고, 없으면 이름 전체를 라벨로 시도한다. <b>어느 쪽이든 아는 넷이 아니면
     * null</b> 이다 — 노선 이름에 다른 꺾쇠 표기가 생기는 날 그것이 방향으로 둔갑하면, 반대
     * 방향 열차를 조용히 고른다.
     */
    public static String labelFrom(String routeLongName) {
        if (routeLongName == null) {
            return null;
        }
        String value = routeLongName.trim();
        int open = value.lastIndexOf('<');
        int close = value.lastIndexOf('>');
        String candidate = open >= 0 && close > open
                ? value.substring(open + 1, close).trim()
                : value;
        return isKnownLabel(candidate) ? candidate : null;
    }

    private static boolean isKnownLabel(String value) {
        return "상행".equals(value) || "하행".equals(value)
                || "내선".equals(value) || "외선".equals(value);
    }
}
