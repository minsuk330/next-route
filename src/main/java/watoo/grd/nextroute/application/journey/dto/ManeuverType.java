package watoo.grd.nextroute.application.journey.dto;

/**
 * 도보 안내점의 회전 종류.
 *
 * <p>TMAP 의 {@code turnType} 숫자를 그대로 내려보내지 않기 위해 두는 값이다. 외부 규격이 바뀌어도
 * 앱 계약은 그대로여야 한다.
 *
 * <p>횡단보도·계단·육교 같은 <b>시설 코드는 별 값을 만들지 않고 {@link #STRAIGHT} 로 접는다.</b>
 * 앱이 잃는 정보는 없다 — 안내 문구({@code instruction})에 "횡단보도를 건넌 후" 가 그대로 남는다.
 * 값을 늘리면 앱이 모르는 값을 만날 위험만 커진다.
 */
public enum ManeuverType {

    /** 출발점. */
    DEPART,

    /** 직진. 시설 진입 안내도 여기로 접는다. */
    STRAIGHT,

    /** 10시 방향. */
    SLIGHT_LEFT,

    /** 좌회전. */
    LEFT,

    /** 8시 방향. */
    SHARP_LEFT,

    /** 2시 방향. */
    SLIGHT_RIGHT,

    /** 우회전. */
    RIGHT,

    /** 4시 방향. */
    SHARP_RIGHT,

    /** 유턴. */
    UTURN,

    /** 도착점. */
    ARRIVE,

    /** 아직 매핑표에 없는 코드. 앱은 안내 문구를 그대로 읽어 준다. */
    UNKNOWN
}
