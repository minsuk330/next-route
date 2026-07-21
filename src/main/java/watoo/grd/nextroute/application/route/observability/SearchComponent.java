package watoo.grd.nextroute.application.route.observability;

/**
 * 경로 검색을 구성하는 의존 단계와 그 등급.
 *
 * <p>등급이 완전성 판정(FULL/PARTIAL/BASE_ONLY/FAILED)의 기준이 된다.
 * MANDATORY 실패는 검색 실패이고, OPTIONAL 실패는 기본 경로를 유지한 채 품질만 낮춘다.
 * AUXILIARY 는 응답 내용에 기여하지 않으므로 완전성 판정에서 제외한다.
 */
public enum SearchComponent {

    /** ODsay 대중교통 경로. 이것이 없으면 응답 자체가 성립하지 않는다. */
    ODSAY(Grade.MANDATORY),
    /** 지하철 폴리라인(DB 캐시). */
    POLYLINE(Grade.OPTIONAL),
    /** TMAP 보행 상세. */
    WALK(Grade.OPTIONAL),
    /** 버스 실시간 도착·위치. */
    BUS_REALTIME(Grade.OPTIONAL),
    /** ML 도착 예측. */
    ML(Grade.OPTIONAL),
    /** 검색 이력 저장. 응답 내용과 무관. */
    SEARCH_LOG(Grade.AUXILIARY);

    public enum Grade { MANDATORY, OPTIONAL, AUXILIARY }

    private final Grade grade;

    SearchComponent(Grade grade) {
        this.grade = grade;
    }

    public Grade grade() {
        return grade;
    }

    public boolean isOptional() {
        return grade == Grade.OPTIONAL;
    }

    /** 메트릭 tag 값(저카디널리티). */
    public String tag() {
        return name().toLowerCase();
    }
}
