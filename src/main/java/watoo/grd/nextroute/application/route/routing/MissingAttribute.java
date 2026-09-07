package watoo.grd.nextroute.application.route.routing;

/**
 * 원천 부재로 채울 수 없는 속성(가짜값 금지). 갭 6종을 개별 사유로 노출한다 —
 * 단일 STATIC_SCHEDULE 로 뭉뚱그리지 않는다(COMPAT-OTP-vs-ODSAY.md).
 */
public enum MissingAttribute {
    EXIT,
    DIRECTION,
    EXPRESS_TYPE,
    COLOR,
    FARE,
    REALTIME_ID
}
