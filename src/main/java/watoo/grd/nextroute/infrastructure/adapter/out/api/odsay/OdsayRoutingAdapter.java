package watoo.grd.nextroute.infrastructure.adapter.out.api.odsay;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.GeometrySource;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 기존 {@link OdSayApiPort} 를 공통 {@link RoutingPort} 로 감싼다(ODsay → 중립 RoutingResult).
 *
 * <p><b>주의</b>: 이 중립 경로는 parity 입증 전 실검색에 배선하지 않는다(PR2c 는 ODsay 를 legacy 직행으로 유지).
 * 표시 geometry(mapObj/lane), 지하철 출구, 노선 색상 등 ODsay 전용 표현은 legacy 응답 경로가 계속 담당하므로
 * 여기서는 구조(경로/구간/정류장/시간/요금)만 옮기고 geometry 는 REVIEW 로 남긴다.
 * -98(700m 이내 도보) fallback 은 legacy {@code RouteSearchService} 정책에 그대로 둔다.
 */
@Component
@RequiredArgsConstructor
public class OdsayRoutingAdapter implements RoutingPort {

    private static final int TRAFFIC_SUBWAY = 1;
    private static final int TRAFFIC_BUS = 2;
    private static final int TRAFFIC_WALK = 3;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final String SCHEMA_VERSION = "odsay-searchPubTransPathT-v1";

    private final OdSayApiPort odSayApiPort;
    private final Clock clock;

    @Override
    public RoutingProvider provider() {
        return RoutingProvider.ODSAY;
    }

    @Override
    public RoutingOutcome search(RoutingQuery query, RoutingDeadline deadline) {
        long budget = deadline.remainingMillis(clock);
        if (budget <= 0) {
            return RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "NO_BUDGET",
                    "ODsay 호출 전에 시간 예산이 소진됨");
        }
        RouteSearchResult raw;
        try {
            // ODsay 좌표계: SX=경도(x), SY=위도(y).
            raw = odSayApiPort.searchPath(query.originLon(), query.originLat(),
                    query.destLon(), query.destLat(), budget);
        } catch (OdSayApiException e) {
            return RoutingFailure.of(kindOf(e), String.valueOf(e.getErrorCode()), e.getMessage());
        }
        return toResult(raw, query);
    }

    private RoutingFailure.Kind kindOf(OdSayApiException e) {
        return switch (e.getKind()) {
            case TIMEOUT -> RoutingFailure.Kind.TIMEOUT;
            case UPSTREAM -> RoutingFailure.Kind.ENGINE_ERROR;
            case BUSINESS -> RoutingFailure.Kind.INPUT_ERROR; // -98 등은 legacy 경로가 도보 fallback 처리
        };
    }

    private RoutingResult toResult(RouteSearchResult raw, RoutingQuery query) {
        List<RoutingItinerary> itineraries = new ArrayList<>();
        if (raw.paths() != null) {
            for (PathResult path : raw.paths()) {
                itineraries.add(toItinerary(path));
            }
        }
        // ODsay 는 graphVersion 이 없다(sentinel). networkDataVersion 도 미상.
        return new RoutingResult(RoutingProvider.ODSAY, SCHEMA_VERSION, null,
                RoutingResult.ODSAY_NO_GRAPH, null, null, query.departureAt(), List.of(), itineraries);
    }

    private RoutingItinerary toItinerary(PathResult path) {
        List<RoutingLeg> legs = new ArrayList<>();
        int moveSeconds = 0;
        int walkSeconds = 0;
        boolean hasTransit = false;
        if (path.subPaths() != null) {
            for (SubPathResult sub : path.subPaths()) {
                RoutingLeg leg = toLeg(sub);
                legs.add(leg);
                int secs = leg.rideDurationSeconds();
                if (leg.mode() == RoutingMode.WALK) {
                    walkSeconds += secs;
                } else {
                    moveSeconds += secs;
                    hasTransit = true;
                }
            }
        }
        PathInfo info = path.info();
        int transferCount = info != null ? info.transferCount() : 0;
        Fare fare = toFare(info, hasTransit);
        // ODsay 는 표시 geometry 를 legacy 경로가 담당하므로 이 중립 결과로는 네비 미승인.
        return new RoutingItinerary(null, moveSeconds, 0, walkSeconds, transferCount,
                false, List.of("ODSAY_NEUTRAL_NO_GEOMETRY"), fare, legs);
    }

    private Fare toFare(PathInfo info, boolean hasTransit) {
        if (info == null) {
            return Fare.unknown();
        }
        int payment = info.payment();
        if (payment > 0) {
            return Fare.known(payment);
        }
        if (payment == 0 && !hasTransit) {
            return Fare.free(); // 도보 전용 진짜 무료
        }
        return Fare.unknown(); // 대중교통인데 0 = 신뢰 못 함(0 을 요금으로 단정하지 않음)
    }

    private RoutingLeg toLeg(SubPathResult sub) {
        RoutingMode mode = switch (sub.trafficType()) {
            case TRAFFIC_SUBWAY -> RoutingMode.SUBWAY;
            case TRAFFIC_BUS -> RoutingMode.BUS;
            case TRAFFIC_WALK -> RoutingMode.WALK;
            default -> RoutingMode.UNSUPPORTED;
        };
        int rideSeconds = sub.sectionTime() * SECONDS_PER_MINUTE;
        List<StopOccurrence> stops = toStops(sub);
        // geometry 는 legacy 경로(mapObj/lane)가 담당 → 중립 결과에서는 REVIEW.
        LegGeometry geometry = new LegGeometry(GeometrySource.NONE, GeometryQuality.REVIEW,
                "ODsay geometry via legacy path", null, null, null);
        RealtimeCoverage coverage = mode == RoutingMode.WALK
                ? RealtimeCoverage.NONE : RealtimeCoverage.STATIC_SCHEDULE;
        return new RoutingLeg(mode, null, null, null, null, null, null, null,
                null, null, rideSeconds, stops, List.of(), geometry,
                Enrichment.staticOnly(coverage), Set.of());
    }

    private List<StopOccurrence> toStops(SubPathResult sub) {
        if (sub.stations() == null || sub.stations().isEmpty()) {
            return List.of();
        }
        List<StopOccurrence> stops = new ArrayList<>();
        int idx = 0;
        for (StationResult st : sub.stations()) {
            stops.add(new StopOccurrence(st.stationID(), null, null, idx++,
                    st.x() != null ? st.x() : 0, st.y() != null ? st.y() : 0, st.stationName()));
        }
        return stops;
    }
}
