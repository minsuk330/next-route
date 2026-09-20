package watoo.grd.nextroute.application.journey.port.out;

import watoo.grd.nextroute.domain.journey.entity.JourneySession;

import java.time.Instant;
import java.util.Optional;

/**
 * 지금 재탐색을 평가해도 되는지.
 *
 * <p>window·cooldown 같은 판정 기준은 전부 이쪽 소유다. 여기 두면 사용자가 버튼을 연타해도
 * 외부 검색이 연타되지 않는다 — 명시 요청도 이 관문을 지난다.
 *
 * <p>구현은 {@code WindowCooldownEligibilityPolicy} 다. 그것이 배선되기 전의 기본값은
 * <b>항상 거절</b>이었다({@code JourneyRerouteConfig}) — 반대로 두면 판정 없이 외부 검색이 돈다.
 */
public interface RerouteEligibilityPolicy {

    /**
     * <b>여정 엔티티를 통째로 받는다.</b> 부르는 쪽이 소유권 확인을 위해 이미 읽어 둔 값이라
     * 조회가 늘지 않고, 무엇보다 {@code routeRevision}·{@code currentLegIndex} 가 여기 있다.
     *
     * <p>식별자만 받던 시절에는 <b>이미 지나간 환승 요청을 거를 수 없었다</b>. 시간 조건만 보면
     * 30분 전에 지나간 환승도 "준비 시각까지 600초 이내"로 통과하고, 요청 본문이 비면
     * {@code transferIndex} 가 0 으로 채워져 옛 앱 버전이 첫 환승 제안을 만들어 버린다(R2).
     *
     * @return 거절 사유. 비어 있으면 허용. 사유 문자열은 지표 태그로 쓰이므로 값 종류가 적어야
     *         한다 — 식별자를 담으면 시계열이 사용자 수만큼 늘어난다. 새 사유는 반드시
     *         {@code JourneyRerouteMetrics} 의 목록에도 넣는다. 안 넣으면 조용히
     *         {@code unknown} 으로 접혀 운영에서 아무것도 못 본다
     */
    Optional<String> denialReason(JourneySession journey, int transferIndex, Instant now);
}
