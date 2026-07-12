package watoo.grd.nextroute.application.route.observability;

/**
 * 응답 완전성. Step 0 에서는 메트릭 tag 로만 쓰고, 응답 DTO 로 노출하지 않는다.
 * (클라이언트 노출과 API 계약 문서화는 Step 5 의 범위 — PLAN.md §5 참고)
 */
public enum SearchOutcome {

    /** 이 요청에 적용 가능한 보강이 전부 성공. */
    FULL,
    /** 일부 보강이 생략·실패. 기본 경로는 정상 반환. */
    PARTIAL,
    /** 적용 가능한 보강이 전부 생략·실패. ODsay 기본 경로만 반환. */
    BASE_ONLY,
    /** 필수 단계(ODsay) 실패로 경로를 만들지 못함. */
    FAILED;

    public String tag() {
        return name().toLowerCase();
    }
}
