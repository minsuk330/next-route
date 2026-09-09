package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;

import java.util.List;

/**
 * 주행 중 재탐색 ③(같은 정류장 다른 노선)의 <b>대안 후보 출처</b>(national-otp P2-F).
 *
 * <p>지금까지 후보는 journey 시작 때 저장한 {@code journey_leg_route_option} 하나뿐이었다. PR4b 는
 * 여기에 OTP pattern 에서 실시간으로 찾은 후보를 더한다. 그 갈래를 이 포트로 가른다 — 소비자
 * ({@link RouteOptionCandidateProvider})는 "후보가 어디서 왔는지" 모른 채 평가만 한다.
 *
 * <p>이 PR(PR-2)에는 저장 구현({@link StoredRouteOptionSource}) 하나뿐이라 행위가 바뀌지 않는다.
 * OTP 구현·union·게이트는 PR-4.
 */
public interface RerouteRouteOptionSource {

    /**
     * 이 대기 leg 의 대안 후보(및 baseline 판정에 쓰는 선택 노선을 포함한 전체 목록).
     *
     * @param waitLeg  {@code WAIT_BUS} 대기 leg
     * @param deadline OTP 등 외부 조회의 호출별 예산(저장 구현은 무시). 소진 시 {@link SourceOutcome#timeout()}
     * @return 정상(0건 포함)/장애를 구분한 결과. 예외로 던지지 않는다
     */
    SourceOutcome optionsFor(JourneyLeg waitLeg, RoutingDeadline deadline);

    /**
     * 조회 결과. <b>정상 0건과 장애를 구분한다</b>(codex 리뷰 B7) — 빈 목록만 돌리면 소비자가
     * "대안 없음"과 "출처 장애"를 못 가른다.
     */
    record SourceOutcome(Status status, List<JourneyLegRouteOption> options) {

        public enum Status { OK, UNAVAILABLE, TIMEOUT, ERROR }

        public SourceOutcome {
            options = options == null ? List.of() : List.copyOf(options);
        }

        public static SourceOutcome ok(List<JourneyLegRouteOption> options) {
            return new SourceOutcome(Status.OK, options);
        }

        public static SourceOutcome unavailable() {
            return new SourceOutcome(Status.UNAVAILABLE, List.of());
        }

        public static SourceOutcome timeout() {
            return new SourceOutcome(Status.TIMEOUT, List.of());
        }

        public static SourceOutcome error() {
            return new SourceOutcome(Status.ERROR, List.of());
        }

        public boolean isOk() {
            return status == Status.OK;
        }
    }
}
