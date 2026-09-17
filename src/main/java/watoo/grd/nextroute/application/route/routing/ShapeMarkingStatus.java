package watoo.grd.nextroute.application.route.routing;

/**
 * 기동 시 형상 마킹의 유효 상태(P3 PR-B2 배포 원자성 게이트). "조용한 fallback" 을 명시적 상태로
 * 드러내, 잘못된 배포 조합(그래프↔coverage-index↔graphVersion 어긋남)을 부팅 로그로 즉시 잡는다.
 *
 * <p>{@link #ACTIVE} 하나만 실제로 SHAPE_OK 를 발행할 수 있는 상태다. 나머지는 전량 STREET_FALLBACK.
 */
public enum ShapeMarkingStatus {
    /** shapesEnabled=false. 형상 마킹 자체가 꺼짐(현행 프로덕션 동작). */
    DISABLED,
    /** shapesEnabled=true 지만 coverage-index 부재/로드실패(disabled index). */
    NO_INDEX,
    /** shapesEnabled=true, 인덱스는 있으나 graphVersion 이 설정과 불일치 → 잘못된 배포 조합. */
    GRAPH_VERSION_MISMATCH,
    /** 형상 마킹 정상 작동(플래그 on + 인덱스 있음 + graphVersion 일치). */
    ACTIVE;

    /** SHAPE_OK 발행이 실제로 가능한 상태인가. */
    public boolean isActive() {
        return this == ACTIVE;
    }
}
