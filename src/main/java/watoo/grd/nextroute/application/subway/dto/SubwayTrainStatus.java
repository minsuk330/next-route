package watoo.grd.nextroute.application.subway.dto;

/**
 * 실시간 위치 API 가 말하는 열차의 현재 상태({@code trainSttus}).
 *
 * <p>원시 문자열을 응용 계층 밖으로 흘리지 않으려고 여기서 닫는다. 다만 <b>모르는 값을 예외로
 * 만들지 않는다</b> — 외부 API 가 코드를 하나 늘리는 순간 수집 자체가 죽으면 안 된다.
 * 모르는 값은 {@link #UNKNOWN} 으로 받고, 전이 판정에서는 아무 근거도 되지 못한다.
 */
public enum SubwayTrainStatus {

    /** 0 — 해당 역으로 진입 중. */
    ENTERING,

    /** 1 — 해당 역에 도착(정차). */
    ARRIVED,

    /** 2 — 해당 역을 출발. */
    DEPARTED,

    /**
     * 3 — 전역출발. 그 row 의 {@code statnId} 역의 <b>바로 전역</b>을 출발해 {@code statnId} 로 가는 중이다
     * (사용자 확정 2026-10-09, 도착 API {@code arvlCd=3} 과 같은 뜻). 역 도착 응답은 이 뜻으로 위치를 그린다(#317).
     *
     * <p><b>이 값만으로 출발 사건을 만들지는 않는다</b>(M7 트래커·링은 아직 이 값을 근거로 쓰지 않는다 — 별건).
     */
    LEFT_PREVIOUS,

    /** 명세에 없는 값. 전이 판정에서 근거가 되지 못한다. */
    UNKNOWN;

    public static SubwayTrainStatus from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim()) {
            case "0" -> ENTERING;
            case "1" -> ARRIVED;
            case "2" -> DEPARTED;
            case "3" -> LEFT_PREVIOUS;
            default -> UNKNOWN;
        };
    }

    /** 열차가 그 역에 있다고 볼 수 있는 상태인지. 출발 전이의 <b>선행 근거</b>가 된다. */
    public boolean isAtStation() {
        return this == ENTERING || this == ARRIVED;
    }
}
