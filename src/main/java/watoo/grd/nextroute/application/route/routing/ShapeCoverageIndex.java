package watoo.grd.nextroute.application.route.routing;

import java.util.Set;

/**
 * P3 pfaedle 형상 품질 인덱스(그래프와 함께 배포되는 coverage-index.json 의 인메모리 표현).
 *
 * <p>OTP 는 shape 없는 trip 도 정류장 직선 polyline 을 돌려주므로 "points 존재 = 진짜 형상"
 * 판단이 불가능하다. 어느 trip 이 도로 추종 형상(SHAPE_OK)인지는 <b>오직 이 인덱스로만</b> 판정한다.
 *
 * <p>인덱스는 <b>degraded trip 만</b> 담는다(게이트 미통과 ∪ 미shape). 통과 trip 은 목록에 없으므로,
 * graphVersion 이 맞고 mode 가 커버되면 기본 SHAPE_OK 다. 즉 인덱스가 낡거나 그래프와 어긋나면
 * {@link #isShapeOk} 는 항상 false 를 돌려 <b>under-claim(전량 fallback)</b> 만 하고, 절대 오마킹하지 않는다.
 */
public final class ShapeCoverageIndex {

    private final String graphVersion;          // 빈 문자열이면 비활성(아무 것도 커버 안 함)
    private final Set<String> coveredModes;     // 예: {"bus"} — GTFS mode 토큰
    private final Set<String> degradedTripIds;  // feed prefix 없는 raw GTFS trip_id

    public ShapeCoverageIndex(String graphVersion, Set<String> coveredModes, Set<String> degradedTripIds) {
        this.graphVersion = graphVersion == null ? "" : graphVersion;
        this.coveredModes = Set.copyOf(coveredModes);
        this.degradedTripIds = Set.copyOf(degradedTripIds);
    }

    /** 형상 마킹 없이 도는 비활성 인덱스(파일 부재/로드 실패/플래그 off 시). */
    public static ShapeCoverageIndex disabled() {
        return new ShapeCoverageIndex("", Set.of(), Set.of());
    }

    /**
     * 이 trip 이 SHAPE_OK 자격이 있는가.
     *
     * @param activeGraphVersion 현재 그래프 버전(설정값). 인덱스 graphVersion 과 정확히 같아야 함
     * @param modeToken          GTFS mode 토큰(bus/subway/rail)
     * @param tripGtfsId         OTP leg 의 trip gtfsId(feed prefix "1:" 포함 가능 — 내부에서 strip)
     * @return graphVersion 일치 ∧ mode 커버 ∧ trip 미degraded 일 때만 true
     */
    public boolean isShapeOk(String activeGraphVersion, String modeToken, String tripGtfsId) {
        if (graphVersion.isEmpty() || activeGraphVersion == null
                || !graphVersion.equals(activeGraphVersion)) {
            return false;
        }
        if (modeToken == null || !coveredModes.contains(modeToken)) {
            return false;
        }
        if (tripGtfsId == null) {
            return false;
        }
        return !degradedTripIds.contains(stripFeed(tripGtfsId));
    }

    private static String stripFeed(String gtfsId) {
        int colon = gtfsId.indexOf(':');
        return colon >= 0 ? gtfsId.substring(colon + 1) : gtfsId;
    }

    /**
     * 기동 시 이 인덱스+설정 조합의 유효 형상 마킹 상태(PR-B2 배포 게이트). 로그/검증용.
     * {@link #isShapeOk} 의 graphVersion·활성 판정과 같은 규칙을 배포 관점에서 요약한다.
     *
     * @param shapesEnabled      설정 킬스위치
     * @param configGraphVersion 설정 graphVersion(배포된 그래프 버전)
     */
    public ShapeMarkingStatus markingStatus(boolean shapesEnabled, String configGraphVersion) {
        if (!shapesEnabled) {
            return ShapeMarkingStatus.DISABLED;
        }
        if (graphVersion.isEmpty()) {
            return ShapeMarkingStatus.NO_INDEX;
        }
        if (configGraphVersion == null || !graphVersion.equals(configGraphVersion)) {
            return ShapeMarkingStatus.GRAPH_VERSION_MISMATCH;
        }
        return ShapeMarkingStatus.ACTIVE;
    }

    public String graphVersion() {
        return graphVersion;
    }

    public Set<String> coveredModes() {
        return coveredModes;
    }

    public int degradedCount() {
        return degradedTripIds.size();
    }
}
