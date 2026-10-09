package watoo.grd.nextroute.application.route.mapping;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.subway.repository.NearbySubwayStationProjection;
import watoo.grd.nextroute.domain.subway.repository.SubwayStationRepository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 방법 B: OTP(GTFS) leg → 서울 provider 식별을 <b>요청시 규칙으로 즉석 해석</b>한다(versioned 매핑 표 미사용).
 *
 * <p>검증 근거(2026-09-08 실 DB×OTP fixture 대조, implementation_note 참조):
 * <ul>
 *   <li>버스 = 결정적. OTP {@code 1:BS_TAGO_SEB<9d>} → bus_stop.stop_id, {@code 1:BR_TAGO_SEB<9d>} → bus_route.route_id.
 *       prefix strip 후 DB 존재 검증(없으면 phantom → empty).</li>
 *   <li>지하철 = 좌표. OTP stop id 는 지선코드(예 부평=경인선 18)라 statn_id 와 대응규칙 없음 → id decode 금지.
 *       route shortName {@code (\d)호선}(1~9)로 line_id({@code 100}+N) 확정 + 좌표 최근접 subway_station(≤{@value #SUBWAY_RADIUS_M}m).</li>
 * </ul>
 *
 * <p><b>스코프 하드제약: 서울 버스 + 지하철 1~9호선만.</b> 경기(BS_TAGO_GGB)·인천(BS_TAGO_ICB)·
 * 기타 노선(경의중앙 1063/신분당 1075 등)·비버스/비지하철은 empty → 호출자가 STATIC_SCHEDULE 로 처리한다.
 * 추후 수도권/전국 확장 시 동일 규칙 성립 여부는 <b>미검증</b>(prefix·좌표계·라인코드 상이 가능) — 확장 전 재실측 필요.
 *
 * <p>예외: 경기 버스는 {@link #resolveGyeonggiBus}(GBIS), 서울·경기 밖 버스는 {@link #resolveTagoBus}(TAGO)로
 * 따로 해석한다(둘 다 DB 검증 없음). 위 서울 스코프는 그대로다.
 */
@Slf4j
@Component
public class OtpSeoulIdentityResolver {

    /** OTP 좌표와 역 좌표가 실측 ~3m 였다. 승강장/역사중심 차이·측정오차 여유로 50m. */
    static final double SUBWAY_RADIUS_M = 50.0;
    private static final int NEARBY_LIMIT = 8;

    private static final Pattern BUS_STOP = Pattern.compile("^\\d+:BS_TAGO_SEB(\\d{9})$");
    // 공항버스(6xxx)는 방향별로 route 가 갈려 _U/_D 접미사가 붙는다. 서울 노선 id 는 앞 9자리.
    private static final Pattern BUS_ROUTE = Pattern.compile("^\\d+:BR_TAGO_SEB(\\d{9})(?:_[UD])?$");
    // 경기도 같은 규칙: 9자리 = GBIS stationId/routeId, 방향 분할 노선은 _U/_D(1,138 중 28개).
    // 경기 마을버스는 노선만 다른 기관 코드로 온다: 1:BR_3100_<GBIS 9자리>(827개, 경기 탑승지점의 19%). 정류장은 GGB 그대로.
    private static final Pattern GG_BUS_STOP = Pattern.compile("^\\d+:BS_TAGO_GGB(\\d{9})$");
    private static final Pattern GG_BUS_ROUTE = Pattern.compile("^\\d+:BR_(?:TAGO_GGB|3100_)(\\d{9})(?:_[UD])?$");
    // 서울·경기 밖: TAGO 는 접두사 포함 id 를 그대로 받는다. 구미(GMB)는 노선 id 에 하이픈이 섞인다(GMB171-110).
    private static final Pattern TAGO_BUS_STOP = Pattern.compile("^\\d+:BS_TAGO_(([A-Z]{3})\\d+)$");
    private static final Pattern TAGO_BUS_ROUTE = Pattern.compile("^\\d+:BR_TAGO_(([A-Z]{3})[\\dA-Z-]+?)(?:_[UD])?$");
    // 앞자리 숫자 lookbehind 로 두 자리 호선("11호선")의 뒷자리 오매치를 막는다(현재 서울 1~9호선만 스코프).
    private static final Pattern SUBWAY_LINE = Pattern.compile("(?<!\\d)([1-9])호선");

    private final BusStopRepository busStopRepo;
    private final BusRouteRepository busRouteRepo;
    private final SubwayStationRepository subwayRepo;
    private final TagoCityCodeLookup tagoCityCodeLookup;

    public OtpSeoulIdentityResolver(BusStopRepository busStopRepo,
                                    BusRouteRepository busRouteRepo,
                                    SubwayStationRepository subwayRepo,
                                    TagoCityCodeLookup tagoCityCodeLookup) {
        this.busStopRepo = busStopRepo;
        this.busRouteRepo = busRouteRepo;
        this.subwayRepo = subwayRepo;
        this.tagoCityCodeLookup = tagoCityCodeLookup;
    }

    /** leg mode 로 분기. 대중교통 아니거나 스코프 밖이면 empty(→ 호출자 STATIC). */
    public Optional<SeoulTransitIdentity> resolve(RoutingLeg leg, StopOccurrence boardingStop) {
        if (leg == null || boardingStop == null) {
            return Optional.empty();
        }
        return switch (leg.mode()) {
            case BUS -> resolveBus(boardingStop.sourceStopId(), leg.sourceRouteId(), leg.sourceRouteName())
                    .map(SeoulTransitIdentity.class::cast);
            case SUBWAY, RAIL -> resolveSubway(leg.sourceRouteName(), boardingStop.lat(), boardingStop.lon())
                    .map(SeoulTransitIdentity.class::cast);
            default -> Optional.empty();
        };
    }

    /** 서울 버스: SEB prefix strip + DB 존재검증. stop/route 둘 다 있어야 실시간 대상. */
    public Optional<SeoulBusIdentity> resolveBus(String stopGtfsId, String routeGtfsId, String routeName) {
        String stopId = capture(BUS_STOP, stopGtfsId);
        String routeId = capture(BUS_ROUTE, routeGtfsId);
        if (stopId == null || routeId == null) {
            return Optional.empty();
        }
        if (!busStopRepo.existsByStopId(stopId) || !busRouteRepo.existsByRouteId(routeId)) {
            return Optional.empty();
        }
        return Optional.of(new SeoulBusIdentity(stopId, routeId, routeName));
    }

    /**
     * 경기 버스: GGB prefix(+방향 접미사) strip. DB 검증 없음 — {@link GyeonggiBusIdentity} 참고.
     * {@link #resolveBus}·{@link #resolve} 의 서울 전용 스코프는 바꾸지 않는다(다른 호출자가 서울 DB 존재를 전제).
     */
    public Optional<GyeonggiBusIdentity> resolveGyeonggiBus(String stopGtfsId, String routeGtfsId, String routeName) {
        String stationId = capture(GG_BUS_STOP, stopGtfsId);
        String routeId = capture(GG_BUS_ROUTE, routeGtfsId);
        if (stationId == null || routeId == null) {
            return Optional.empty();
        }
        return Optional.of(new GyeonggiBusIdentity(stationId, routeId, routeName));
    }

    /**
     * 서울·경기 밖 버스: {@code 1:BS_TAGO_}/{@code 1:BR_TAGO_} 만 떼고 접두사는 남긴다({@link TagoBusIdentity}).
     * 정류장·노선 접두사가 같고 노선의 도시코드를 알 때만({@link TagoCityCodeLookup}). GGB 는 앞선 경기 분기가
     * 먼저 잡고, SEB 는 도시코드가 없어 자연히 제외된다.
     */
    public Optional<TagoBusIdentity> resolveTagoBus(String stopGtfsId, String routeGtfsId, String routeName) {
        Matcher stop = match(TAGO_BUS_STOP, stopGtfsId);
        Matcher route = match(TAGO_BUS_ROUTE, routeGtfsId);
        if (stop == null || route == null || !stop.group(2).equals(route.group(2))) {
            return Optional.empty();
        }
        return tagoCityCodeLookup.cityCodeOfRoute(route.group(1))
                .map(city -> new TagoBusIdentity(city, stop.group(1), route.group(1), routeName));
    }

    /**
     * 정류장 하나의 버스 도착 조회 id(#313). 노선 없이 정류장만 본다 — 주변 정류장 목록용.
     *
     * <p>서울은 DB 에 있을 때만(SEB 9자리), 경기는 GBIS 9자리, 그 밖은 TAGO 접두사 포함 id({@code ICB121000541}).
     * 도착 API({@code BusArrivalQueryService})가 이 모양으로 원천을 고른다. 모르면 빈 값.
     */
    public Optional<String> busStopArrivalId(String stopGtfsId) {
        String seoul = capture(BUS_STOP, stopGtfsId);
        if (seoul != null) {
            return busStopRepo.existsByStopId(seoul) ? Optional.of(seoul) : Optional.empty();
        }
        String gyeonggi = capture(GG_BUS_STOP, stopGtfsId);
        if (gyeonggi != null) {
            return Optional.of(gyeonggi);
        }
        Matcher tago = match(TAGO_BUS_STOP, stopGtfsId);
        return tago == null ? Optional.empty() : Optional.of(tago.group(1));
    }

    /** 노선 하나의 버스 도착 조회 id(#313). 규칙은 {@link #busStopArrivalId} 와 같다(방향 접미 {@code _U/_D} 제거). */
    public Optional<String> busRouteArrivalId(String routeGtfsId) {
        String seoul = capture(BUS_ROUTE, routeGtfsId);
        if (seoul != null) {
            return busRouteRepo.existsByRouteId(seoul) ? Optional.of(seoul) : Optional.empty();
        }
        String gyeonggi = capture(GG_BUS_ROUTE, routeGtfsId);
        if (gyeonggi != null) {
            return Optional.of(gyeonggi);
        }
        Matcher tago = match(TAGO_BUS_ROUTE, routeGtfsId);
        return tago == null ? Optional.empty() : Optional.of(tago.group(1));
    }

    /** 서울 지하철 1~9호선: route 호선 + 좌표 최근접(라인 일치). */
    public Optional<SeoulSubwayIdentity> resolveSubway(String routeName, double lat, double lon) {
        String lineId = seoulLineId(routeName);
        if (lineId == null) {
            return Optional.empty();
        }
        List<NearbySubwayStationProjection> nearby =
                subwayRepo.findNearby(lat, lon, SUBWAY_RADIUS_M, NEARBY_LIMIT);
        // findNearby 는 거리 오름차순. 목표 라인 첫 후보 = 최근접.
        return nearby.stream()
                .filter(p -> lineId.equals(p.getLineId()))
                .findFirst()
                .map(p -> new SeoulSubwayIdentity(p.getStationId(), p.getLineId(),
                        p.getDistMeters() == null ? Double.NaN : p.getDistMeters()));
    }

    private static Matcher match(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.matches() ? m : null;
    }

    private static String capture(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        return m.matches() ? m.group(1) : null;
    }

    /** "서울4호선" → "1004". 1~9 만. 그 밖(경의중앙/신분당/숫자없음)은 null(스코프 밖). */
    private static String seoulLineId(String routeName) {
        if (routeName == null) {
            return null;
        }
        Matcher m = SUBWAY_LINE.matcher(routeName);
        if (!m.find()) {
            return null;
        }
        int n = m.group(1).charAt(0) - '0';
        if (n < 1 || n > 9) {
            return null;
        }
        return "100" + n;
    }
}
