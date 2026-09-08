package watoo.grd.nextroute.application.route.service;

import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.util.ArrayList;
import java.util.List;

/**
 * 중립 {@link RoutingResult} → 기존 앱이 소비하는 legacy {@link RouteSearchResult}.
 *
 * <p>OTP 경로가 구형 앱과 호환되게 하려는 <b>API 경계 전용</b> 매퍼다. ODsay 는 parity 전 legacy 직행을
 * 유지하므로 이 매퍼는 주로 OTP 결과에 쓰인다.
 *
 * <p><b>채우지 못하는 legacy 필드(원천 부재, 가짜값 금지)</b>:
 * <ul>
 *   <li>{@code polyline}/{@code laneGraphics}/{@code mapObj} — 대중교통 형상은 P3 OSM relation 이 채운다(지금 null).</li>
 *   <li>{@code walkSteps} — legacy 는 TMAP turn 구조(x/y 포함)라 OTP steps 와 형태가 달라 배선하지 않는다(후속).</li>
 *   <li>ODsay 전용 ID(localStationID/ArsID/exit) — GTFS 매핑(P2-D) 전이라 null.</li>
 *   <li>{@code totalWalk}(m) — 중립 모델이 거리(m)를 안 들고 있어 0. 시간(초)만 정확.</li>
 * </ul>
 * 요금은 unknown 을 0 으로 만들지 않는다: KNOWN→금액, KNOWN_ZERO→0, UNKNOWN→{@code -1} sentinel.
 */
@Component
public class LegacyRouteResponseMapper {

    /** legacy payment(primitive int) 의 unknown sentinel. 구형 앱은 -1 을 "요금 미상"으로 그린다(P5 확인 대상). */
    public static final int PAYMENT_UNKNOWN = -1;

    private static final int TRAFFIC_SUBWAY = 1;
    private static final int TRAFFIC_BUS = 2;
    private static final int TRAFFIC_WALK = 3;
    private static final int SECONDS_PER_MINUTE = 60;

    public RouteSearchResult toLegacy(RoutingResult result) {
        List<PathResult> paths = new ArrayList<>();
        int busCount = 0;
        int subwayCount = 0;
        int trainCount = 0;
        for (RoutingItinerary it : result.itineraries()) {
            paths.add(toPath(it));
            boolean hasBus = it.legs().stream().anyMatch(l -> l.mode() == RoutingMode.BUS);
            boolean hasSubway = it.legs().stream().anyMatch(l -> l.mode() == RoutingMode.SUBWAY);
            boolean hasRail = it.legs().stream().anyMatch(l -> l.mode() == RoutingMode.RAIL);
            if (hasBus) busCount++;
            if (hasSubway) subwayCount++;
            if (hasRail) trainCount++;
        }
        // searchType: ODsay 는 요청 검색모드(0=지하철+버스 등)를 에코한다. OTP 경로는 그 개념이 없어
        // 0(미상)으로 둔다 — busCount/subwayCount/trainCount 처럼 결과에서 파생할 값이 아니다.
        return new RouteSearchResult(0, busCount, subwayCount, trainCount, 0, paths);
    }

    private PathResult toPath(RoutingItinerary it) {
        int totalTimeMin = Math.round((it.moveSeconds() + it.waitSeconds() + it.walkSeconds())
                / (float) SECONDS_PER_MINUTE);
        PathInfo info = new PathInfo(
                totalTimeMin,
                toLegacyPayment(it.fare()),
                0,                                  // totalWalk(m): 중립 모델에 거리 없음 → 0
                it.transferCount(),
                firstStationName(it),
                lastStationName(it),
                null);                              // mapObj: ODsay 전용
        List<SubPathResult> subPaths = new ArrayList<>();
        for (RoutingLeg leg : it.legs()) {
            subPaths.add(toSubPath(leg));
        }
        return new PathResult(pathType(it), info, subPaths, List.<LaneGraphicResult>of());
    }

    private int toLegacyPayment(Fare fare) {
        return switch (fare.availability()) {
            case KNOWN -> fare.amount();
            case KNOWN_ZERO -> 0;
            case UNKNOWN -> PAYMENT_UNKNOWN;
        };
    }

    private int pathType(RoutingItinerary it) {
        boolean bus = it.legs().stream().anyMatch(l -> l.mode() == RoutingMode.BUS);
        boolean subway = it.legs().stream()
                .anyMatch(l -> l.mode() == RoutingMode.SUBWAY || l.mode() == RoutingMode.RAIL);
        if (bus && subway) return 3;   // 버스+지하철
        if (subway) return 1;          // 지하철
        return 2;                       // 버스(또는 도보만)
    }

    private SubPathResult toSubPath(RoutingLeg leg) {
        int trafficType = switch (leg.mode()) {
            case SUBWAY, RAIL -> TRAFFIC_SUBWAY;
            case BUS -> TRAFFIC_BUS;
            case WALK -> TRAFFIC_WALK;
            case UNSUPPORTED -> 0;
        };
        int sectionTime = Math.round(leg.rideDurationSeconds() / (float) SECONDS_PER_MINUTE);
        List<StationResult> stations = toStations(leg.stops());
        Double startX = leg.stops().isEmpty() ? null : leg.stops().get(0).lon();
        Double startY = leg.stops().isEmpty() ? null : leg.stops().get(0).lat();
        StopOccurrence last = leg.stops().isEmpty() ? null : leg.stops().get(leg.stops().size() - 1);
        Double endX = last == null ? null : last.lon();
        Double endY = last == null ? null : last.lat();
        String startName = leg.stops().isEmpty() ? null : leg.stops().get(0).name();
        String endName = last == null ? null : last.name();

        // 채우지 못하는 legacy 필드는 null(가짜값 금지): polyline/exit/walkSteps/ODsay-id.
        return new SubPathResult(
                trafficType, sectionTime, null, null, stations,
                startName, endName, startX, startY, endX, endY,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null);
    }

    private List<StationResult> toStations(List<StopOccurrence> stops) {
        List<StationResult> stations = new ArrayList<>();
        for (StopOccurrence s : stops) {
            stations.add(new StationResult(s.occurrenceIndex(), s.sourceStopId(), s.name(),
                    s.lon(), s.lat()));
        }
        return stations;
    }

    private String firstStationName(RoutingItinerary it) {
        for (RoutingLeg leg : it.legs()) {
            if (leg.isTransit() && !leg.stops().isEmpty()) {
                return leg.stops().get(0).name();
            }
        }
        return "";
    }

    private String lastStationName(RoutingItinerary it) {
        for (int i = it.legs().size() - 1; i >= 0; i--) {
            RoutingLeg leg = it.legs().get(i);
            if (leg.isTransit() && !leg.stops().isEmpty()) {
                return leg.stops().get(leg.stops().size() - 1).name();
            }
        }
        return "";
    }
}
