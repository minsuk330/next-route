package watoo.grd.nextroute.application.journey.dto;

/**
 * Dynamic Island 가 그리는 국면. <b>둘뿐이다.</b>
 *
 * <p>늘리고 싶은 유혹이 계속 생기지만(대기·환승·지연) 앱 위젯이 이 두 값에 맞춰 만들어져 있고,
 * 모르는 값이 오면 갱신이 <b>조용히 무시</b>된다.
 */
public enum SubwayRingPhase {

    /** 열차에 타고 이동 중. */
    RIDE_SUBWAY,

    /** 다음 역이 하차역이다. 내릴 준비를 할 때. */
    SUBWAY_ALIGHT
}
