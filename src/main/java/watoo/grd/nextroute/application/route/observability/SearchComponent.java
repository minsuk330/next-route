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
    /** TMAP 보행 상세. */
    WALK(Grade.OPTIONAL),
    /** 버스 실시간 도착·위치. */
    BUS_REALTIME(Grade.OPTIONAL),
    /** ML 도착 예측. */
    ML(Grade.OPTIONAL),
    /**
     * 급행 끄기(subway-express S2). 요청이 급행을 뺐는데 일반 경로가 0건이라 필터를 풀었다(D10) — 응답 경로에 급행이 있다.
     * 앱은 "급행 포함 결과" 안내를 띄운다. 필터가 지켜졌으면 적용만 표시(requested)한다.
     */
    EXPRESS_FILTER(Grade.OPTIONAL),
    /** 급행 끄기에서 일반 대안 다시 묻기가 실패·시간 초과라 확인하지 못한 채 필터를 풀었다(D10). 앱 안내는 위와 같다. */
    EXPRESS_FILTER_UNVERIFIED(Grade.OPTIONAL),
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
