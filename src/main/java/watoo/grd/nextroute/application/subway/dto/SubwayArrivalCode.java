package watoo.grd.nextroute.application.subway.dto;

/**
 * 서울 실시간 <b>도착</b> API 의 {@code arvlCd}. 값은 전부 <b>조회한 역 기준</b>이다.
 *
 * <h2>왜 남은 초보다 이 코드를 먼저 보나</h2>
 *
 * <p>{@code barvlDt}(남은 초)는 실측 표본의 절반이 0 인데, <b>0 의 뜻이 이 코드에 따라
 * 정반대</b>다.
 *
 * <ul>
 *   <li>{@link #ENTERING}·{@link #ARRIVED} + 0 → 다 왔다(사건)</li>
 *   <li>{@link #ENROUTE} + 0 → 아직 멀다(미산출. 문구가 {@code [N]번째 전역})</li>
 * </ul>
 *
 * <p>초를 먼저 보면 <b>3정거장 전 열차를 90% 로 표시</b>한다. 그래서 판정은 언제나 코드가
 * 먼저다.
 *
 * <p>모르는 값을 예외로 만들지 않는다 — 외부 API 가 코드를 하나 늘리는 순간 링이 죽으면 안 된다.
 * 모르는 값은 {@link #UNKNOWN} 이고 아무 진행도 만들지 못한다.
 */
public enum SubwayArrivalCode {

    /** 0 — 조회역 <b>진입</b>. 아직 문이 안 열렸다. */
    ENTERING,

    /** 1 — 조회역 <b>도착</b>. 이 구간은 끝났다. */
    ARRIVED,

    /** 2 — 조회역 <b>출발</b>. 우리 기준으로는 그 역을 이미 지났다는 뜻이다. */
    DEPARTED,

    /** 3 — 전역 출발. 조회역 기준으로는 <b>구간의 시작</b>이다. */
    PREV_DEPARTED,

    /** 4 — 전역 진입. */
    PREV_ENTERING,

    /** 5 — 전역 도착. */
    PREV_ARRIVED,

    /** 99 — 운행 중. 초가 있으면 {@code M분 S초 후}, 없으면 {@code [N]번째 전역} 이다. */
    ENROUTE,

    /** 명세에 없는 값. 진행 판정의 근거가 되지 못한다. */
    UNKNOWN;

    public static SubwayArrivalCode from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim()) {
            case "0" -> ENTERING;
            case "1" -> ARRIVED;
            case "2" -> DEPARTED;
            case "3" -> PREV_DEPARTED;
            case "4" -> PREV_ENTERING;
            case "5" -> PREV_ARRIVED;
            case "99" -> ENROUTE;
            default -> UNKNOWN;
        };
    }

    /** 조회역 <b>직전</b>을 가리키는 코드인지. 우리 구간으로는 막 시작한 자리다. */
    public boolean isPreviousStation() {
        return this == PREV_DEPARTED || this == PREV_ENTERING || this == PREV_ARRIVED;
    }
}
