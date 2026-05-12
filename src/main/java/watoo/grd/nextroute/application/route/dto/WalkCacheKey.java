package watoo.grd.nextroute.application.route.dto;

/**
 * TMAP 보행자 경로 캐시 키.
 * 좌표 라운딩 1e-4 (≈ 11m) 정밀도로 정규화한 문자열 키를 생성한다.
 */
public record WalkCacheKey(
        double startX,
        double startY,
        double endX,
        double endY,
        int searchOption
) {

    public static final double COORD_ROUNDING = 1e-4;

    /**
     * 캐시 조회/저장에 사용할 정규화된 키 문자열.
     * 형식: coord:{rx}:{ry}|coord:{rx}:{ry}|opt:{N}
     */
    public String asKey() {
        return "coord:" + round(startX) + ":" + round(startY)
                + "|coord:" + round(endX) + ":" + round(endY)
                + "|opt:" + searchOption;
    }

    private static String round(double v) {
        // 1e-4 round to 4 decimal places
        return String.format("%.4f", Math.round(v * 10000.0) / 10000.0);
    }
}
