package watoo.grd.nextroute.application.journey.dto;

/**
 * 도보 안내점의 회전 종류.
 *
 * <p>TMAP 의 {@code turnType} 숫자를 그대로 내려보내지 않기 위해 두는 값이다. 외부 규격이 바뀌어도
 * 앱 계약은 그대로여야 한다.
 *
 * <p>육교·지하보도·계단·경사로·엘리베이터처럼 <b>방향이 없는 시설 코드는 별 값을 만들지 않고
 * {@link #STRAIGHT} 로 접는다.</b> 앱이 잃는 정보는 없다 — 안내 문구({@code instruction})에 시설
 * 이름이 그대로 남는다. 값을 늘리면 앱이 모르는 값을 만날 위험만 커진다.
 *
 * <p>횡단보도만 예외로 {@link #CROSSWALK} 를 둔다. TMAP 보행자 규격에 횡단보도 코드가 일곱 개
 * (211~217) 있고 실제 응답에 흔히 나온다. 건너는 행동을 "직진"이라 부르면 거짓이고, 앱은 이때
 * 아이콘을 달리 줘야 한다.
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

    /** 횡단보도를 건넌다. 어느 쪽·어느 방향 횡단보도인지는 안내 문구에 있다. */
    CROSSWALK,

    /** 도착점. */
    ARRIVE,

    /** 아직 매핑표에 없는 코드. 앱은 안내 문구를 그대로 읽어 준다. */
    UNKNOWN
}
