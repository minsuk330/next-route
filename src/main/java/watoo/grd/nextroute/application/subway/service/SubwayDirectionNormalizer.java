package watoo.grd.nextroute.application.subway.service;

/**
 * 서울 실시간 위치 API 의 {@code updnLine} 을 도착정보와 같은 표기로 맞춘다.
 *
 * <p><b>왜 별도 클래스인가.</b> 이미 방향을 만드는 코드가 있다 —
 * {@code SubwayRealtimeArrivalReader.toDirection}. 하지만 그것은 <b>ODsay 의 {@code wayCode}</b>
 * 를 받는다. 서울의 {@code updnLine} 과 값 규칙이 달라서, 재사용하면 방향이 통째로 뒤집힌다.
 * 반대 방향 열차를 고르면 ETA 도 탑승 판정도 <b>그럴듯하게</b> 틀린다.
 *
 * <pre>
 * ODsay wayCode  : 1 → 상행,  그 외 → 하행      (2호선은 0 → 내선, 그 외 → 외선)
 * 서울 updnLine  : 0 → 상행,  1     → 하행      (2호선은 0 → 내선, 1     → 외선)
 * </pre>
 *
 * <p>숫자와 뜻의 대응이 서로 반대다. 한 함수에 합치려는 순간 둘 중 하나가 틀린다.
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

    private static boolean isKnownLabel(String value) {
        return "상행".equals(value) || "하행".equals(value)
                || "내선".equals(value) || "외선".equals(value);
    }
}
