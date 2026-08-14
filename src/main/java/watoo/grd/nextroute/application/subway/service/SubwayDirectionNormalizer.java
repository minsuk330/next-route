package watoo.grd.nextroute.application.subway.service;

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
 * <p><b>미검증</b>: 위 {@code updnLine} 규칙은 공식 명세 표기를 따른 것이고 실제 응답으로
 * 확인하지 않았다. 실측 전까지 이 매핑을 다른 곳에 복제하지 않는다 — 고칠 자리가 하나여야 한다.
 */
public final class SubwayDirectionNormalizer {

    /** 2호선. 상·하행이 아니라 내·외선으로 말한다. */
    private static final String CIRCULAR_LINE_ID = "1002";

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

    private static boolean isKnownLabel(String value) {
        return "상행".equals(value) || "하행".equals(value)
                || "내선".equals(value) || "외선".equals(value);
    }
}
