package watoo.grd.nextroute.application.route.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.v2.FareV2;
import watoo.grd.nextroute.application.route.dto.v2.PathResultV2;
import watoo.grd.nextroute.application.route.dto.v2.RouteSearchResultV2;
import watoo.grd.nextroute.application.route.dto.v2.SubPathResultV2;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.FareAvailability;
import watoo.grd.nextroute.application.route.routing.MissingAttribute;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 중립 {@link RoutingResult} → capability(v2) 응답. provider/버전·nullable 요금·coverage 를 그대로 노출한다
 * (legacy 처럼 0/-1 로 뭉개지 않음). 진입점 배선은 후속 PR.
 */
@Component
public class RouteSearchV2Mapper {

    public RouteSearchResultV2 toV2(RoutingResult result) {
        List<PathResultV2> paths = new ArrayList<>();
        for (RoutingItinerary it : result.itineraries()) {
            paths.add(toPath(it));
        }
        return new RouteSearchResultV2(result.provider().name(), result.schemaVersion(),
                result.graphVersion(), result.departureAt(), paths);
    }

    private PathResultV2 toPath(RoutingItinerary it) {
        List<SubPathResultV2> subPaths = new ArrayList<>();
        for (RoutingLeg leg : it.legs()) {
            subPaths.add(toSub(leg));
        }
        int total = it.moveSeconds() + it.waitSeconds() + it.walkSeconds();
        return new PathResultV2(total, it.moveSeconds(), it.waitSeconds(), it.walkSeconds(),
                it.transferCount(), toFare(it.fare()), it.navigationSupported(),
                List.copyOf(it.navigationBlockedReasons()), subPaths);
    }

    private FareV2 toFare(Fare fare) {
        Integer amount = fare.availability() == FareAvailability.UNKNOWN ? null : fare.amount();
        return new FareV2(fare.availability().name(), amount);
    }

    private SubPathResultV2 toSub(RoutingLeg leg) {
        List<String> missing = leg.missing().stream().map(MissingAttribute::name).toList();
        return new SubPathResultV2(
                leg.mode().name(),
                leg.sourceDetailType(),
                leg.sourceRouteId(),
                leg.sourceRouteName(),
                leg.rideDurationSeconds(),
                leg.enrichment().coverage().name(),
                leg.enrichment().actualSource().name(),
                leg.geometry().quality().name(),
                missing);
    }
}
