package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 환승 정류장에서 목적지까지 <b>다시 길찾기</b>. ④에서 처음으로 유료 검색이 나가는 곳이다.
 *
 * <p><b>{@code RouteSearchService} 를 부르지 않는다.</b> 그쪽은 검색이력 저장·telemetry 같은
 * 부수효과를 달고 있어 여정 경로에 섞이면 사용자의 "검색 기록"에 누른 적 없는 검색이 쌓인다.
 * 시작점도 사용자가 입력한 출발지가 아니라 곧 내릴 정류장이다.
 *
 * <h2>캐시를 두지 않는다</h2>
 * <p>재탐색은 <b>계약상 실시간</b>이다(실시간 판정은 주행 재탐색에서만). "이 정류장→목적지 경로 구조"를
 * TTL 동안 공유하면 이미 떠난 편·바뀐 상황을 굳은 값으로 돌려주게 되어 재탐색의 목적과 어긋난다.
 * 그래서 매 재탐색은 <b>그때 상태로 직접 provider(OTP/ODSAY)를 돌린다</b>. OTP 과부하는 캐시가 아니라
 * 글로벌 동시성 gate(permit N, 검색과 합산)로 막는다.
 *
 * <p>검색이 실패해도 <b>예외를 올리지 않고 빈 목록</b>이다. 재검색은 "있으면 좋은" 기능이라
 * 검색이 죽었다고 재탐색 요청 전체를 실패시키지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RemainingRouteSearchService {

    private final OdSayApiPort odSayApiPort;
    private final JourneyRerouteMetrics metrics;
    // provider 전환(national-otp P2). 기본 ODSAY 라 재탐색도 default 에서는 기존 ODsay 직행.
    private final RoutingProviderSelector providerSelector;
    private final LegacyRouteResponseMapper legacyRouteResponseMapper;
    // OTP 글로벌 동시성 상한(검색과 합산 N). 기본 provider=ODSAY 라 default 에선 dormant.
    private final OtpConcurrencyGate otpConcurrencyGate;
    // OTP 호출 예산 단일소스(api 검색과 공유). ODSAY 재탐색은 계속 budgetMs 사용.
    private final watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties routeSearchDeadlineProperties;
    private final Clock clock;

    /**
     * @param stopId    곧 내릴 정류장(로그·telemetry 용). <b>미해석(null)이면 부르면 안 된다</b>
     * @param budgetMs  이 검색에 쓸 수 있는 시간(ODSAY 경로에만 적용)
     * @return 검색이 실패하면 빈 목록
     */
    public List<PathResult> search(String stopId, Waypoints waypoints, long budgetMs) {
        RoutingProvider provider = providerSelector.select(false);
        List<PathResult> paths;
        try {
            paths = provider == RoutingProvider.OTP
                    ? loadOtpGated(waypoints)
                    : loadOdsay(waypoints, budgetMs);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 남은 구간 검색 실패 [{}]: {}", stopId, e.getMessage());
            metrics.recordRemainingSearch("failed");
            return List.of();
        }
        if (paths.isEmpty()) {
            metrics.recordRemainingSearch("failed");
            return List.of();
        }
        metrics.recordRemainingSearch("called");
        return paths;
    }

    /**
     * OTP 경로는 글로벌 동시성 gate 를 거친다. deadline 하나를 gate 대기와 OTP 호출이 공유해
     * 예산 이중소비를 막는다. 포화·Redis 장애(fail-closed) → 빈 목록(재탐색 실패 계약, OTP-only 라
     * ODSAY 폴백 없음).
     */
    private List<PathResult> loadOtpGated(Waypoints waypoints) {
        // OTP 호출은 전용 예산(otpMs) 단일소스 — ODSAY 재탐색 예산(budgetMs)과 분리.
        RoutingDeadline deadline = RoutingDeadline.of(clock, routeSearchDeadlineProperties.getOtpMs());
        Optional<OtpPermit> permit = otpConcurrencyGate.tryAcquire(deadline, "worker");
        if (permit.isEmpty()) {
            metrics.recordRemainingSearch("otp-saturated");
            return List.of();
        }
        try {
            return loadOtp(waypoints, deadline);
        } finally {
            otpConcurrencyGate.release(permit.get());
        }
    }

    /** 기본 provider(ODSAY): 기존 직행 유지(parity). */
    private List<PathResult> loadOdsay(Waypoints waypoints, long budgetMs) {
        RouteSearchResult result = odSayApiPort.searchPath(
                waypoints.origin().x(), waypoints.origin().y(),
                waypoints.destination().x(), waypoints.destination().y(), budgetMs);
        if (result == null || result.paths() == null) {
            return List.of();
        }
        return result.paths();
    }

    /**
     * OTP 경로: RoutingPort → legacy paths. 실패는 kind 를 계측하되 재탐색 계약대로 빈 목록으로 끝낸다
     * (실패가 재탐색 요청 전체를 죽이지 않음). 출발시각은 현재 시각 — 예상 환승 도착시각 전달은 시그니처
     * 변경이 필요한 후속 작업이다.
     */
    private List<PathResult> loadOtp(Waypoints waypoints, RoutingDeadline deadline) {
        RoutingQuery query = new RoutingQuery(
                waypoints.origin().x(), waypoints.origin().y(), null,
                waypoints.destination().x(), waypoints.destination().y(), null,
                clock.instant(), Set.of(), null, false, "otp-v2", RoutingProvider.OTP, null);
        RoutingOutcome outcome = providerSelector.portFor(RoutingProvider.OTP).search(query, deadline);
        if (outcome instanceof RoutingFailure failure) {
            metrics.recordRemainingSearch("otp-failed-" + failure.kind());
            return List.of();
        }
        return legacyRouteResponseMapper.toLegacy((RoutingResult) outcome).paths();
    }
}
