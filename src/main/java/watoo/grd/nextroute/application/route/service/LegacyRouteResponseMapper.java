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
import watoo.grd.nextroute.application.route.routing.AlternativeRoute;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.subway.service.SubwayLineColors;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Duration;
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
    /** 지하철 역마다 갈아탈 노선(#510). null 이면(수동 조립) 싣지 않는다. */
    private final SubwayLineStationIndex stationIndex;

    /** 경기 노선 유형은 번들 목록으로 읽는다(테스트·수동 조립용). */
    public LegacyRouteResponseMapper(OtpSeoulIdentityResolver resolver) {
        this(resolver, new GyeonggiBusRouteTypeColors());
    }

    public LegacyRouteResponseMapper(OtpSeoulIdentityResolver resolver, GyeonggiBusRouteTypeColors gyeonggiColors) {
        this(resolver, gyeonggiColors, null);
    }

    @Autowired
    public LegacyRouteResponseMapper(OtpSeoulIdentityResolver resolver, GyeonggiBusRouteTypeColors gyeonggiColors,
                                     SubwayLineStationIndex stationIndex) {
        this.resolver = resolver;
        this.gyeonggiColors = gyeonggiColors;
        this.stationIndex = stationIndex;
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
        RoutingLeg previous = null;
        for (RoutingLeg leg : it.legs()) {
            subPaths.add(withPlannedTimes(toSubPath(leg), leg, previous));
            previous = leg;
        }
        return new PathResult(pathType(it), info, subPaths, List.<LaneGraphicResult>of());
    }

    /**
     * 대중교통 구간에 OTP 계획 탑승·하차와 계획 대기를 싣는다(search-regional-bus-wait PR-1a). 계획 대기 = 탑승 − 직전 구간 끝 —
     * OTP {@code waitingTime} 이 정확히 이 간격들의 합이다(첫 대중교통 구간 앞 여유 포함, 2026-11-09 운영 OTP 실측). 도보 구간과
     * 시각이 없는 구간은 그대로 둔다. {@link #toSubPath} 는 구간 하나만 봐서(단독 추적이 재사용) 직전 구간을 알 수 없어 여기서 한다.
     */
    static SubPathResult withPlannedTimes(SubPathResult sp, RoutingLeg leg, RoutingLeg previous) {
        if (leg.mode() == RoutingMode.WALK || leg.mode() == RoutingMode.UNSUPPORTED || leg.boardAt() == null) {
            return sp;
        }
        Integer wait = previous != null && previous.alightAt() != null
                ? (int) Math.max(0, Duration.between(previous.alightAt(), leg.boardAt()).getSeconds())
                : null;
        return sp.withPlanned(leg.boardAt(), leg.alightAt(), wait);
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
        // 지하철 구간이면 그 노선(실시간 line_id)을 빼고 역마다 갈아탈 노선을 싣는다(#510). 여정 단계와 같은 해석이다.
        String subwayLineId = leg.mode() == RoutingMode.SUBWAY || leg.mode() == RoutingMode.RAIL
                ? SubwayLineIdMapper.resolveOdsay(leg.sourceRouteName(), leg.sourceRouteId()).orElse(null)
                : null;
        List<StationResult> stations = toStations(leg.stops(), subwayLineId);
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
            String boardStopId = leg.boardStopId();
            String alightStopId = leg.alightStopId();
            BusIds ids = boardStopId == null ? BusIds.NONE
                    : resolveBusIds(boardStopId, alightStopId, leg.sourceRouteId(), routeName, true);
            startLocalStationID = ids.startStationId();
            endLocalStationID = ids.endStationId();
            // 표시용 lane 은 버스번호로 항상 생성. busLocalBlID(실시간 호출용)는 매핑될 때만 non-null.
            List<LaneResult> busLanes = new ArrayList<>(1 + leg.alternatives().size());
            busLanes.add(new LaneResult(routeName, routeName, null, null, null, ids.routeLocalId(),
                    displayColor(leg)));
            // 같은 승하차 정류장의 다른 노선(otp-bus-lane-merge, BusLaneMerger 가 거른 최종 목록). 정류장 id 는 구간 공통이라
            // 대표 값을 쓰고, 노선마다 실시간용 노선 id 만 해석한다. 미매핑이어도 lane 은 남긴다(번호 표시).
            for (AlternativeRoute alt : leg.alternatives()) {
                BusIds altIds = boardStopId == null ? BusIds.NONE
                        : resolveBusIds(boardStopId, alightStopId, alt.routeId(), alt.routeName(), false);
                // 구간 정류장 id 는 대표 지역(서울·경기·TAGO) 값이다. 다른 지역 노선 id 를 붙이면 실시간 조회가 서울 정류장 +
                // 경기 노선처럼 짝이 안 맞는다 → 같은 지역일 때만 싣고, 아니면 번호만 보인다.
                String altId = altIds.region() != null && altIds.region() == ids.region() ? altIds.routeLocalId() : null;
                busLanes.add(new LaneResult(alt.label(), alt.label(), null, null, null, altId,
                        displayColor(RoutingMode.BUS, alt.routeId(), alt.routeName(), alt.routeColor())));
            }
            lanes = List.copyOf(busLanes);
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
                null, startLocalStationID, endLocalStationID, null, null, null, null, null, trainKind, null, null, null);
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
     * 먼저다(#391) — 직행좌석 4403 처럼 M 이 아닌 광역 노선도 빨강이 된다. 지하철은 공식 노선색 표({@link SubwayLineColors})가 먼저다.
     */
    private String displayColor(RoutingLeg leg) {
        return displayColor(leg.mode(), leg.sourceRouteId(), leg.sourceRouteName(), leg.sourceRouteColor());
    }

    private String displayColor(RoutingMode mode, String routeId, String routeName, String sourceColor) {
        if (mode == RoutingMode.BUS) {
            Optional<String> gyeonggi = gyeonggiColors.color(routeId);
            if (gyeonggi.isPresent()) {
                return gyeonggi.get();
            }
            if (isMetropolitanExpressBus(routeName)) {
                return METROPOLITAN_EXPRESS_BUS_COLOR;
            }
        }
        if (mode == RoutingMode.SUBWAY || mode == RoutingMode.RAIL) {
            // 지하철은 공식 노선색 표가 먼저다(OTP 그래프 색 대신). 표 밖 일반 철도(KTX·ITX)만 그래프 색.
            Optional<String> official = SubwayLineColors.byLineName(routeName);
            if (official.isPresent()) {
                return official.get();
            }
        }
        return sourceColor == null ? null : "#" + sourceColor;
    }

    /** 버스 구간의 실시간 호출용 id(서울 → 경기 → TAGO 순으로 첫 매핑). 못 찾으면 전부 null. */
    private enum BusRegion { SEOUL, GYEONGGI, TAGO }

    private record BusIds(String startStationId, String endStationId, String routeLocalId, BusRegion region) {
        static final BusIds NONE = new BusIds(null, null, null, null);
    }

    /**
     * 승차 정류장 + 노선으로 실시간 id 를 해석한다. 서울이면 서울 stop/route id, 아니면 경기(서버가 서울 순번이 없는 조합을
     * 경기 API 로 보낸다), 그 밖은 접두사 포함 TAGO id(BSB…, 서버가 id 모양으로 TAGO 를 고른다).
     *
     * @param withAlight 하차 정류장 id 도 해석할지(대표 노선만 — 대체 노선은 노선 id 만 쓴다)
     */
    private BusIds resolveBusIds(String boardStopId, String alightStopId, String routeId, String routeName,
                                 boolean withAlight) {
        Optional<SeoulBusIdentity> seoul = resolver.resolveBus(boardStopId, routeId, routeName);
        if (seoul.isPresent()) {
            String end = withAlight && alightStopId != null
                    ? resolver.resolveBus(alightStopId, routeId, routeName).map(SeoulBusIdentity::stopId).orElse(null)
                    : null;
            return new BusIds(seoul.get().stopId(), end, seoul.get().routeId(), BusRegion.SEOUL);
        }
        Optional<GyeonggiBusIdentity> gg = resolver.resolveGyeonggiBus(boardStopId, routeId, routeName);
        if (gg.isPresent()) {
            String end = withAlight && alightStopId != null
                    ? resolver.resolveGyeonggiBus(alightStopId, routeId, routeName)
                            .map(GyeonggiBusIdentity::stationId).orElse(null)
                    : null;
            return new BusIds(gg.get().stationId(), end, gg.get().routeId(), BusRegion.GYEONGGI);
        }
        Optional<TagoBusIdentity> tago = resolver.resolveTagoBus(boardStopId, routeId, routeName);
        if (tago.isEmpty()) {
            return BusIds.NONE;
        }
        String end = withAlight && alightStopId != null
                ? resolver.resolveTagoBus(alightStopId, routeId, routeName).map(TagoBusIdentity::nodeId).orElse(null)
                : null;
        return new BusIds(tago.get().nodeId(), end, tago.get().routeId(), BusRegion.TAGO);
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

    /** @param subwayLineId 지하철 구간의 실시간 line_id. null 이면(버스·모르는 노선) 환승을 싣지 않는다 */
    private List<StationResult> toStations(List<StopOccurrence> stops, String subwayLineId) {
        List<StationResult> stations = new ArrayList<>();
        for (StopOccurrence s : stops) {
            stations.add(new StationResult(s.occurrenceIndex(), s.sourceStopId(), s.name(),
                    s.lon(), s.lat(), transfersAt(s, subwayLineId)));
        }
        return stations;
    }

    private List<StationResult.Transfer> transfersAt(StopOccurrence stop, String subwayLineId) {
        if (stationIndex == null || subwayLineId == null) {
            return null;
        }
        return stationIndex.transfersAt(stop.name(), stop.lat(), stop.lon(), subwayLineId)
                .map(transfers -> transfers.stream()
                        .map(t -> new StationResult.Transfer(t.lineId(), t.lineName()))
                        .toList())
                .orElse(null);
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
