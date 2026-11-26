package watoo.grd.nextroute.common.observability;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 합성 모니터링 요청(alert-analyzer 의 호출기)을 알아보는 규칙.
 *
 * <p>호출기는 10분마다 실제 사용자처럼 경로 검색과 도착 조회를 부른다(docs/plan/synthetic-monitoring).
 * 이 요청이 사용 퍼널({@code route_search_event})과 요청 통계에 섞이면, 하루 약 20건인 실제 검색보다 많아
 * 숫자가 망가진다. 그래서 헤더 하나로 표시하고, 기록과 지표에서 가른다.
 *
 * <p>헤더는 누구나 붙일 수 있다. 붙이면 통계에서 빠질 뿐 권한·응답은 그대로라 위조해도 얻는 게 없다.
 */
public final class SyntheticRequest {

    public static final String HEADER = "X-Nextroute-Synthetic";

    private SyntheticRequest() {
    }

    public static boolean isSynthetic(String headerValue) {
        return "1".equals(headerValue == null ? null : headerValue.trim());
    }

    public static boolean isSynthetic(HttpServletRequest request) {
        return request != null && isSynthetic(request.getHeader(HEADER));
    }
}
