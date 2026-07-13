package watoo.grd.nextroute.application.route.observability;

/**
 * 보강이 생략·실패한 이유. 메트릭 tag 로 쓰이므로 저카디널리티 고정 집합만 둔다.
 * (좌표·노선 ID·requestId 등 요청별로 달라지는 값은 tag 가 될 수 없다.)
 */
public enum DegradeReason {

    /** 단계 상한 또는 전체 deadline 초과. */
    TIMEOUT,
    /** provider 호출 예외(연결 실패·5xx·파싱 실패 포함). */
    UPSTREAM_ERROR,
    /** provider 가 차단 상태(circuit breaker open, 일일한도 등). */
    BLOCKED,
    /** quota·동시 슬롯·per-search 호출 상한으로 호출하지 못함. */
    LIMITED,
    /** 캐시(Redis/DB)에 데이터가 없고 보강 데이터를 확보하지 못함. */
    NO_DATA,
    /** provider 는 정상 응답했지만 내용이 비어 보강할 수 없음(서비스 지역 외 등). */
    EMPTY_RESULT,
    /** provider 전용 풀이 포화되어 호출하지 못함. */
    BULKHEAD_FULL,
    /** 기능 플래그로 꺼져 있음. */
    DISABLED,
    /** 부가 작업(검색 이력 등) 저장 실패. */
    PERSIST_ERROR;

    public String tag() {
        return name().toLowerCase();
    }
}
