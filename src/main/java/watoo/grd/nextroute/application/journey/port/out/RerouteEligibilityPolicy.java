package watoo.grd.nextroute.application.journey.port.out;

import java.time.Instant;
import java.util.Optional;

/**
 * 지금 재탐색을 평가해도 되는지. 트리거 PR(①)이 구현한다.
 *
 * <p>window·cooldown·최소 개선폭 같은 판정 기준은 전부 이쪽 소유다. 여기 두면 사용자가 버튼을
 * 연타해도 외부 검색이 연타되지 않는다 — 명시 요청도 이 관문을 지난다.
 *
 * <p>기본 구현은 <b>항상 거절</b>이다({@code JourneyRerouteConfig}). 반대로 두면 ①이 늦어지는
 * 동안 아무 제한 없이 외부 검색이 돈다.
 */
public interface RerouteEligibilityPolicy {

    /**
     * @return 거절 사유. 비어 있으면 허용. 사유 문자열은 지표 태그로 쓰이므로 값 종류가 적어야
     *         한다 — 식별자를 담으면 시계열이 사용자 수만큼 늘어난다
     */
    Optional<String> denialReason(Long journeyId, int transferIndex, Instant now);
}
