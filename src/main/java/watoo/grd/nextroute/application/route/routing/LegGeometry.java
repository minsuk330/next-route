package watoo.grd.nextroute.application.route.routing;

/**
 * 구간 형상. 표시용({@code displayPoints})과 진행 원본({@code progressPoints})을 분리한다.
 * source/quality/reason/version 을 보존하고, 결측/단절은 빈 형상 + 사유로 둔다(직선 연결 금지).
 *
 * @param displayPoints  표시용 인코딩 폴리라인(단순화 가능). 없으면 null
 * @param progressPoints 진행 판정용 원본 인코딩 폴리라인. 없으면 null
 */
public record LegGeometry(
        GeometrySource source,
        GeometryQuality quality,
        String reason,
        String version,
        String displayPoints,
        String progressPoints
) {

    public static LegGeometry missing(String reason) {
        return new LegGeometry(GeometrySource.NONE, GeometryQuality.MISSING, reason, null, null, null);
    }
}
