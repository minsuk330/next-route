package watoo.grd.nextroute.domain.journey.entity;

/**
 * 여정의 생사. 값 목록은 V35 의 {@code journey_session_status_check} 가 소유한다.
 *
 * <p>{@code ACTIVE} 만 "사람당 하나" 제약의 대상이다. 끝난 여정은 몇 개가 쌓여도 상관없다.
 */
public enum JourneyStatus {

    /** 진행 중. 사용자당 최대 하나. */
    ACTIVE,

    /** 목적지에 도착해 정상 종료. */
    COMPLETED,

    /** 사용자나 관리자가 중단. 새 여정으로 갈아탄 경우도 여기에 들어간다. */
    CANCELED,

    /** 앱이 오래 소식이 없어 서버가 접은 경우. */
    EXPIRED
}
