package watoo.grd.nextroute.application.subway.dto;

/**
 * 열차 종류({@code directAt}). 급행은 서는 역이 다르므로 후보 판정에서 일반 열차와 섞으면 안 된다.
 *
 * <p>{@link SubwayTrainStatus} 와 같은 이유로 모르는 값을 예외로 만들지 않는다.
 */
public enum SubwayExpressType {

    /** 0 — 일반. */
    NORMAL,

    /** 1 — 급행. */
    EXPRESS,

    /** 7 — 특급. */
    SPECIAL,

    /** 명세에 없는 값. 안전한 쪽으로 다뤄야 하므로 일반으로 승격하지 않는다. */
    UNKNOWN;

    public static SubwayExpressType from(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        return switch (raw.trim()) {
            case "0" -> NORMAL;
            case "1" -> EXPRESS;
            case "7" -> SPECIAL;
            default -> UNKNOWN;
        };
    }
}
