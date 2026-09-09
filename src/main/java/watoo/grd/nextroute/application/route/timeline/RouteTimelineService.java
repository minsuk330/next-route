package watoo.grd.nextroute.application.route.timeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * OTP 결과의 각 itinerary 를 {@link RouteTimelineComposer} 로 합성해 <b>보강 total 로 비교·정렬</b>한다(PLAN P2-E).
 * 실시간은 주입된 {@link RealtimeLegTimingLookup}(현재 버스; coverage 로 게이트됨) 로 붙는다.
 *
 * <p>정렬: FEASIBLE 을 앞에, 그 안에서 effectiveTotal 오름차순, 동률/불가는 원래 순서 유지(안정).
 * 불가(MISSED/CANCELLED/NO_CATCHABLE) itinerary 는 뒤로 보낸다(재검색·제외는 P2-F). 응답 매핑은 바꾸지 않는다.
 *
 * <p><b>예산(M1)</b>: 실시간 lookup 은 leg 마다 외부 API 를 부를 수 있어 N×L 순차 호출이 검색 deadline 을
 * 넘길 수 있다. {@code budgetMillis} 로 wall-clock 상한을 걸어 소진 후에는 실시간을 붙이지 않고 정적으로
 * 합성한다(누적 실시간 지연을 대략 budget + 진행 중 1콜 로 제한). 개별 콜 timeout 을 잔여로 줄이는
 * 완전한 decaying budget 은 {@code timingFor} 시그니처 확장이 필요해 후속(P7 OTP 활성 전).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteTimelineService {

    private final RouteTimelineComposer composer;
    private final RealtimeLegTimingLookup realtimeLookup;
    private final Clock clock;

    public RoutingResult reorderByEffectiveTotal(RoutingResult result, Instant departureAt, long budgetMillis) {
        List<RoutingItinerary> itineraries = result.itineraries();
        if (itineraries.size() <= 1) {
            return result;
        }
        // 예산 소진 후엔 실시간을 붙이지 않고 정적으로. budget<=0 이면 처음부터 정적(실시간 skip).
        Instant realtimeDeadline = budgetMillis > 0 ? clock.instant().plusMillis(budgetMillis) : Instant.MIN;
        RealtimeLegTimingLookup gated = (leg, stop, board) ->
                clock.instant().isBefore(realtimeDeadline)
                        ? realtimeLookup.timingFor(leg, stop, board)
                        : Optional.empty();

        record Scored(RoutingItinerary itinerary, ComposedTimeline timeline, int index) {}
        try {
            List<Scored> scored = new ArrayList<>(itineraries.size());
            for (int i = 0; i < itineraries.size(); i++) {
                ComposedTimeline t = composer.compose(itineraries.get(i), departureAt, gated);
                scored.add(new Scored(itineraries.get(i), t, i));
            }
            scored.sort(Comparator
                    .comparingInt((Scored s) -> s.timeline().feasible() ? 0 : 1)
                    .thenComparingInt(s -> s.timeline().feasible()
                            ? s.timeline().effectiveTotalSeconds() : Integer.MAX_VALUE)
                    .thenComparingInt(Scored::index));
            return result.withItineraries(scored.stream().map(Scored::itinerary).toList());
        } catch (RuntimeException e) {
            // 실시간 조회/보강 중 예외(DB·외부콜 등)는 검색 전체를 죽이지 않는다 → 정적(원래) 순서로 degrade.
            log.warn("ETA 합성/정렬 실패 → 정적 순서 유지: {}", e.toString());
            return result;
        }
    }
}
