package watoo.grd.nextroute.application.route.routing;

/**
 * 형상 품질. P3 OSM relation 전의 OTP street 형상은 {@link #STREET_FALLBACK} 이며
 * <b>정상(OSM_RELATION_OK) 으로 노출하지 않는다</b>(handoffs/PHASE-1.md). 네비 진행 확정은 OK 만 허용.
 */
public enum GeometryQuality {
    OSM_RELATION_OK,
    STREET_FALLBACK,
    MISSING,
    REVIEW
}
