package watoo.grd.nextroute.application.route.routing;

/** 요금 가용성. 도보전용 진짜 무료(KNOWN_ZERO)와 원천부재(UNKNOWN)를 구분한다. 0 을 unknown 으로 쓰지 않는다. */
public enum FareAvailability {
    KNOWN,
    KNOWN_ZERO,
    UNKNOWN
}
