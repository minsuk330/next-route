package watoo.grd.nextroute.application.route.routing;

/** 형상 출처. 원천 provenance 를 바꾸지 않는다(예: SEOUL_SECTION 을 OSM 으로 둔갑 금지). */
public enum GeometrySource {
    OSM_RELATION,
    /** pfaedle map-matched GTFS shape (P3 Stage1, 버스). */
    GTFS_SHAPE,
    OTP_STREET,
    SEOUL_SECTION,
    NONE
}
