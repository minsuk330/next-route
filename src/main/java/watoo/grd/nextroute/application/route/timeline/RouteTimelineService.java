package watoo.grd.nextroute.application.route.timeline;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * OTP 결과의 각 itinerary 를 {@link RouteTimelineComposer} 로 합성해 <b>보강 total 로 비교·정렬</b>한다(PLAN P2-E).
 * 실시간은 주입된 {@link RealtimeLegTimingLookup}(현재 버스; coverage 로 게이트됨) 로 붙는다.
 *
 * <p>정렬: FEASIBLE 을 앞에, 그 안에서 effectiveTotal 오름차순, 동률/불가는 원래 순서 유지(안정).
 * 불가(MISSED/CANCELLED/NO_CATCHABLE) itinerary 는 뒤로 보낸다(재검색·제외는 P2-F). 응답 매핑은 바꾸지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteTimelineService {

    private final RouteTimelineComposer composer;
    private final RealtimeLegTimingLookup realtimeLookup;

    public RoutingResult reorderByEffectiveTotal(RoutingResult result, Instant departureAt) {
        List<RoutingItinerary> itineraries = result.itineraries();
        if (itineraries.size() <= 1) {
            return result;
        }
        record Scored(RoutingItinerary itinerary, ComposedTimeline timeline, int index) {}
        try {
            List<Scored> scored = new ArrayList<>(itineraries.size());
            for (int i = 0; i < itineraries.size(); i++) {
                ComposedTimeline t = composer.compose(itineraries.get(i), departureAt, realtimeLookup);
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
