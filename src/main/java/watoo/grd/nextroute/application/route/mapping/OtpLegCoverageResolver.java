package watoo.grd.nextroute.application.route.mapping;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.util.List;

/**
 * OTP 결과의 대중교통 leg 마다 {@link OtpSeoulIdentityResolver} 로 실시간 매핑 가능 여부를 판정해
 * coverage 를 확정한다(REALTIME_CAPABLE=매핑됨, STATIC_SCHEDULE=수도권 내 미매핑). 방법 B 배선의 토대.
 *
 * <p>매핑되면 이후 실시간/ML 보강 대상이 되고, 안 되면 정적 시간표만 쓴다. 도보/미지원 leg 은 건드리지 않는다.
 * 잘못된 매핑이 실시간을 오염시키지 않도록 resolver 는 존재검증/좌표+라인 확정된 것만 present 를 돌린다.
 */
@Component
@RequiredArgsConstructor
public class OtpLegCoverageResolver {

    private final OtpSeoulIdentityResolver identityResolver;

    public RoutingResult applyCoverage(RoutingResult result) {
        List<RoutingItinerary> itineraries = result.itineraries().stream()
                .map(this::applyItinerary)
                .toList();
        return result.withItineraries(itineraries);
    }

    private RoutingItinerary applyItinerary(RoutingItinerary itinerary) {
        List<RoutingLeg> legs = itinerary.legs().stream()
                .map(this::applyLeg)
                .toList();
        return itinerary.withLegs(legs);
    }

    private RoutingLeg applyLeg(RoutingLeg leg) {
        if (!leg.isTransit()) {
            return leg; // 도보/미지원은 그대로(NONE)
        }
        StopOccurrence boarding = leg.stops().isEmpty() ? null : leg.stops().get(0);
        boolean resolvable = identityResolver.resolve(leg, boarding).isPresent();
        RealtimeCoverage coverage = resolvable
                ? RealtimeCoverage.REALTIME_CAPABLE : RealtimeCoverage.STATIC_SCHEDULE;

        Enrichment cur = leg.enrichment();
        Enrichment updated = cur == null
                ? Enrichment.staticOnly(coverage)
                : new Enrichment(coverage, cur.actualSource(), cur.observedAt(), cur.calculatedAt(), cur.status());
        return leg.withEnrichment(updated);
    }
}
