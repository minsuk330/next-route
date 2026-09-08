package watoo.grd.nextroute.application.route.timeline;

/** ETA 타임라인 구간 종류. WAIT(대기)와 RIDE(승차)를 분리해 대기를 중복 가산하지 않는다. */
public enum SegmentType { WAIT, RIDE, WALK }
