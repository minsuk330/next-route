package watoo.grd.nextroute.domain.journey.entity;

/**
 * 앱이 보낸 command 를 서버가 어떻게 처리했는지. 값 목록은 V35 의
 * {@code journey_event_result_check} 가 소유한다.
 *
 * <p>Phase 2 의 상태머신이 채운다. 이번 단계는 테이블과 매핑만 만든다.
 */
public enum JourneyEventResult {

    /** 상태에 반영됨. */
    APPLIED,

    /** 같은 {@code eventId} 를 이미 처리함(재전송). 응답은 최초 결과와 같다. */
    DUPLICATE,

    /** 앱이 들고 있던 {@code commandVersion} 이 낡아 반영하지 않음. */
    CONFLICT,

    /** 규칙상 허용되지 않는 요청. */
    REJECTED
}
