package watoo.grd.nextroute.application.route.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneGraphicResult;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.mapping.GyeonggiBusIdentity;
import watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver;
import watoo.grd.nextroute.application.route.mapping.SeoulBusIdentity;
import watoo.grd.nextroute.application.route.mapping.TagoBusIdentity;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 중립 {@link RoutingResult} → 기존 앱이 소비하는 legacy {@link RouteSearchResult}.
 *
 * <p>OTP 경로가 구형 앱과 호환되게 하려는 <b>API 경계 전용</b> 매퍼다. ODsay 는 parity 전 legacy 직행을
 * 유지하므로 이 매퍼는 주로 OTP 결과에 쓰인다.
 *
 * <p><b>채우지 못하는 legacy 필드(원천 부재, 가짜값 금지)</b>:
 * <ul>
 *   <li>{@code laneGraphics}/{@code mapObj} — ODsay 전용 표시 데이터라 원천이 없음.</li>
 *   <li>{@code polyline} — 검증된 형상만 채운다: 대중교통은 {@code GeometryQuality.SHAPE_OK} 일 때만,
 *       도보는 항상(OTP OSM 실경로). 그 외(STREET_FALLBACK/MISSING/REVIEW)는 null(가짜 직선 금지).</li>
 *   <li>{@code walkSteps} — legacy 는 TMAP turn 구조(x/y 포함)라 OTP steps 와 형태가 달라 배선하지 않는다(후속).</li>
 *   <li>버스 실시간 ID(localStationID/busLocalBlID) — {@link OtpSeoulIdentityResolver} 로 채운다(프론트가
 *       {@code /api/arrivals/bus/{stopId}/{routeId}} 를 호출하게). 서울 노선은 서울 id, 경기 노선은 GBIS id
 *       (GGB 9자리), 서울·경기 밖은 접두사 포함 TAGO id(BSB…). 매핑 안 되면 null(가짜값 금지).
 *       지하철은 실시간이 좌표 기반({@code /api/arrivals/subway?lat&lon})이라 id 를 채우지 않는다 — 좌표로 충분.
 *       ArsID/exit 는 원천 부재라 여전히 null.</li>
 *   <li>{@code totalWalk}(m)·도보 {@code distance}(m) — OTP leg {@code distance} 를 반올림(#399). leg 거리가 없으면 도보
 *       turn 거리 합, 그것도 없으면 구간 {@code distance} 는 null 이고 {@code totalWalk} 합에서는 빠진다.</li>
 * </ul>
 * 노선 표시색은 그래프 빌드 때 GTFS route_color 로 채워 OTP 가 내려준 값을 쓴다(#247). 예외: 경기 버스는 그래프에
 * 유형 색이 없어 노선 유형으로 고쳐 쓰고({@link GyeonggiBusRouteTypeColors}, #391), 유형을 모르는 M버스는 광역
 * 빨강으로 쓴다. 색이 없어도 lane(노선명)은 항상 내려간다.
 * 요금은 unknown 을 0 으로 만들지 않는다: KNOWN→금액, KNOWN_ZERO→0, UNKNOWN→{@code -1} sentinel.
 */
@Component
public class LegacyRouteResponseMapper {

    /** 서울 광역버스와 같은 빨강({@code seoulBusRouteType} 6). 앱 BusRouteTypeColors 의 광역 값. */
    static final String METROPOLITAN_EXPRESS_BUS_COLOR = "#E60012";
    private static final Pattern METROPOLITAN_EXPRESS_BUS = Pattern.compile("^M\\d{4}(?!\\d)");

    /** legacy payment(primitive int) 의 unknown sentinel. 구형 앱은 -1 을 "요금 미상"으로 그린다(P5 확인 대상). */
    public static final int PAYMENT_UNKNOWN = -1;

    /** 버스 leg 의 서울 실시간 id(정류장/노선) 해석. 지하철은 좌표 기반이라 사용 안 함. */
    private final OtpSeoulIdentityResolver resolver;
    private final GyeonggiBusRouteTypeColors gyeonggiColors;

    /** 경기 노선 유형은 번들 목록으로 읽는다(테스트·수동 조립용). */
    public LegacyRouteResponseMapper(OtpSeoulIdentityResolver resolver) {
        this(resolver, new GyeonggiBusRouteTypeColors());
    }

    @Autowired
    public LegacyRouteResponseMapper(OtpSeoulIdentityResolver resolver, GyeonggiBusRouteTypeColors gyeonggiColors) {
        this.resolver = resolver;
        this.gyeonggiColors = gyeonggiColors;
    }

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
                totalWalkMeters(it),                // totalWalk(m): 도보 구간 거리 합(#399)
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

    /**
     * 구간 하나를 legacy 구간으로. 단독 추적(#265)이 패턴에서 오려 낸 구간도 이 길을 탄다 — 실시간 id·지하철
     * {@code wayCode} 보강을 한 자리에서 하기 위해서다.
     */
    public SubPathResult toSubPath(RoutingLeg leg) {
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

        // 버스 leg 만 서울 실시간 id 를 채운다. 프론트가 /api/arrivals/bus/{stopId}/{routeId} 를 호출한다.
        // 지하철/철도는 좌표 기반 실시간이라 여기서 id 를 안 채운다(startX/Y·stations 좌표로 충분).
        List<LaneResult> lanes = null;
        String startLocalStationID = null;
        String endLocalStationID = null;
        Integer wayCode = null;
        if (leg.mode() == RoutingMode.SUBWAY || leg.mode() == RoutingMode.RAIL) {
            // 방향은 실시간 도착을 <b>한쪽으로 거르는</b> 값이다. 없으면 앱이 반대 방향 열차까지
            // 받아 목록이 두 배가 된다. 원천 라벨(상행·하행·내선·외선)을 ODsay 값 공간으로 옮긴다.
            wayCode = SubwayDirectionNormalizer.toWayCode(leg.sourceRouteDirection());
            String routeName = leg.sourceRouteName();
            if (routeName != null) {
                lanes = List.of(new LaneResult(routeName, null, null, null, null, null,
                        displayColor(leg)));
            }
        } else if (leg.mode() == RoutingMode.BUS) {
            String routeName = leg.sourceRouteName();
            String busLocalBlID = null;
            if (!leg.stops().isEmpty()) {
                Optional<SeoulBusIdentity> boarding = resolver.resolveBus(
                        leg.stops().get(0).sourceStopId(), leg.sourceRouteId(), routeName);
                if (boarding.isPresent()) {
                    startLocalStationID = boarding.get().stopId();
                    busLocalBlID = boarding.get().routeId();
                    endLocalStationID = resolver.resolveBus(
                                    last.sourceStopId(), leg.sourceRouteId(), routeName)
                            .map(SeoulBusIdentity::stopId).orElse(null);
                } else {
                    // 경기 노선: 같은 엔드포인트를 부르면 서버가 서울 순번이 없는 조합을 경기 API 로 보낸다.
                    Optional<GyeonggiBusIdentity> gg = resolver.resolveGyeonggiBus(
                            leg.stops().get(0).sourceStopId(), leg.sourceRouteId(), routeName);
                    if (gg.isPresent()) {
                        startLocalStationID = gg.get().stationId();
                        busLocalBlID = gg.get().routeId();
                        endLocalStationID = resolver.resolveGyeonggiBus(
                                        last.sourceStopId(), leg.sourceRouteId(), routeName)
                                .map(GyeonggiBusIdentity::stationId).orElse(null);
                    } else {
                        // 서울·경기 밖: 접두사 포함 TAGO id(BSB…). 서버가 id 모양으로 TAGO 를 고른다.
                        Optional<TagoBusIdentity> tago = resolver.resolveTagoBus(
                                leg.stops().get(0).sourceStopId(), leg.sourceRouteId(), routeName);
                        startLocalStationID = tago.map(TagoBusIdentity::nodeId).orElse(null);
                        busLocalBlID = tago.map(TagoBusIdentity::routeId).orElse(null);
                        endLocalStationID = tago.isEmpty() ? null : resolver.resolveTagoBus(
                                        last.sourceStopId(), leg.sourceRouteId(), routeName)
                                .map(TagoBusIdentity::nodeId).orElse(null);
                    }
                }
            }
            // 표시용 lane 은 버스번호로 항상 생성. busLocalBlID(실시간 호출용)는 매핑될 때만 non-null.
            lanes = List.of(new LaneResult(routeName, routeName, null, null, null, busLocalBlID,
                    displayColor(leg)));
        }

        List<CoordPoint> polyline = toPolyline(leg);

        // 채우지 못하는 legacy 필드는 null(가짜값 금지): exit/walkSteps/ArsID/지하철-id.
        // wayCode 는 지하철 leg 에서만 채운다 — 버스는 원천에도 방향 코드가 없다.
        Double distance = leg.mode() == RoutingMode.WALK ? walkDistance(leg) : null;
        // 열차 종류는 철도 구간만(subway-express S2). 판정은 어댑터 경계에서 이미 끝났다 — 여기서는 옮기기만 한다.
        String trainKind = (leg.mode() == RoutingMode.SUBWAY || leg.mode() == RoutingMode.RAIL)
                && leg.trainKind() != null ? leg.trainKind().name() : null;
        return new SubPathResult(
                trafficType, sectionTime, distance, lanes, stations,
                startName, endName, startX, startY, endX, endY,
                null, null, null, null, wayCode, polyline,
                null, null, null, null, null, null,
                null, startLocalStationID, endLocalStationID, null, null, null, null, null, trainKind);
    }

    /**
     * 도보 구간 거리(m, 정수로 반올림). leg 거리가 우선이고, 없으면 도보 turn 거리 합. 둘 다 없으면 null — 0 으로
     * 지어내면 앱이 "도보 0m"를 그린다(#399).
     */
    static Double walkDistance(RoutingLeg leg) {
        if (leg.distanceMeters() != null) {
            return (double) Math.round(leg.distanceMeters());
        }
        if (leg.walkSteps().isEmpty()) {
            return null;
        }
        double sum = 0;
        for (var step : leg.walkSteps()) {
            sum += step.distanceMeters();
        }
        return (double) Math.round(sum);
    }

    /** 경로의 도보 거리 합(m). 거리를 모르는 도보 구간은 빠진다. */
    private static int totalWalkMeters(RoutingItinerary it) {
        int total = 0;
        for (RoutingLeg leg : it.legs()) {
            if (leg.mode() != RoutingMode.WALK) {
                continue;
            }
            Double d = walkDistance(leg);
            if (d != null) {
                total += d.intValue();
            }
        }
        return total;
    }

    /**
     * GTFS route_color("B81B30") → legacy 표시색("#B81B30"). 원천에 없으면 null(가짜색 금지). 경기 버스는 노선 유형 색이
     * 먼저다(#391) — 직행좌석 4403 처럼 M 이 아닌 광역 노선도 빨강이 된다.
     */
    private String displayColor(RoutingLeg leg) {
        if (leg.mode() == RoutingMode.BUS) {
            Optional<String> gyeonggi = gyeonggiColors.color(leg.sourceRouteId());
            if (gyeonggi.isPresent()) {
                return gyeonggi.get();
            }
            if (isMetropolitanExpressBus(leg.sourceRouteName())) {
                return METROPOLITAN_EXPRESS_BUS_COLOR;
            }
        }
        String color = leg.sourceRouteColor();
        return color == null ? null : "#" + color;
    }

    /**
     * 수도권 광역급행 M버스(경기 M4130·인천 M6405 등) — 번호가 {@code M} + 숫자 4자리.
     *
     * <p>임시 규칙이다. 그래프 빌드가 노선 종류를 아는 곳은 서울 DB 뿐이라 경기·인천 노선은 전부 기본색(초록)이 된다.
     * KTDB GTFS routes.txt 에 종류 정보가 없어서다. 경기 버스노선 API 로 종류를 받아 그래프에 색을 넣게 되면 지운다.
     * 3자리 이하(경기 마을 M101·세종 M1)는 광역급행이 아니라서 걸리지 않게 4자리로 묶는다.
     */
    private static boolean isMetropolitanExpressBus(String routeName) {
        return routeName != null && METROPOLITAN_EXPRESS_BUS.matcher(routeName).find();
    }

    /**
     * 검증된 형상만 반환한다(가짜 직선 금지). 대중교통은 {@code SHAPE_OK} 게이트 통과 시에만,
     * 도보는 OTP OSM 실경로라 게이트 대상이 아니다. 2점 미만은 null(선은 2점부터 의미가 있음).
     */
    private List<CoordPoint> toPolyline(RoutingLeg leg) {
        boolean eligible = leg.mode() == RoutingMode.WALK
                || (leg.isTransit() && leg.geometry().quality() == GeometryQuality.SHAPE_OK);
        if (!eligible) {
            return null;
        }
        List<CoordPoint> points = EncodedPolyline.decode(leg.geometry().displayPoints());
        return points.size() < 2 ? null : points;
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
