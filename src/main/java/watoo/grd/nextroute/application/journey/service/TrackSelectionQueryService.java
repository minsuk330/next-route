package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitRoute;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.NearStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.service.GyeonggiBusRouteTypeColors;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 단독 추적(#265) 선택 화면 조회: 좌표 → 정류장/역, 정류장 → 노선·방향, 방향 → 하차 후보.
 *
 * <p>플래그({@code journey.track.enabled})가 꺼져 있으면 404 다 — 조회도 OTP 를 부른다.
 */
@Service
@RequiredArgsConstructor
public class TrackSelectionQueryService {

    private final JourneyTrackProperties properties;
    private final TrackPatternSource source;
    private final TrackRealtimeIdResolver realtimeIds;
    private final GyeonggiBusRouteTypeColors gyeonggiColors;

    /**
     * @param mode {@code BUS} 또는 {@code SUBWAY}(철도·경전철 포함). null 이면 전부
     */
    public List<TrackViews.Stop> stopsNear(double lat, double lon, RoutingMode mode) {
        requireEnabled();
        List<NearStop> stops = source.withPermit(deadline -> source.port().stopsNear(lat, lon,
                properties.getNearRadiusMeters(), properties.getNearLimit(), deadline));
        List<TrackViews.Stop> views = new ArrayList<>();
        Map<String, List<NearStop>> groups = siblingGroups(stops);
        Set<String> merged = new HashSet<>();
        for (NearStop near : stops) {
            TransitStop stop = near.stop();
            if (!matches(mode, stop.mode())) {
                continue;
            }
            if (stop.mode() == RoutingMode.BUS) {
                List<NearStop> group = groups.getOrDefault(stop.gtfsId(), List.of(near));
                if (!merged.add(group.get(0).stop().gtfsId())) {
                    continue; // 묶음은 처음 만난(가장 가까운) 자리에 한 번만
                }
                TransitStop representative = group.get(0).stop();
                Integer distance = group.stream().map(NearStop::distanceMeters)
                        .filter(Objects::nonNull).min(Integer::compare).orElse(null);
                // 대표(서울 우선)가 서울 DB 에 없으면 형제(경기·인천)의 도착 id 를 쓴다(#333).
                TrackRealtimeIdResolver.BusStopIds ids = group.stream()
                        .map(member -> realtimeIds.busStop(member.stop().gtfsId()))
                        .filter(found -> found.arrivalStopId() != null)
                        .findFirst()
                        .orElse(TrackRealtimeIdResolver.BusStopIds.NONE);
                views.add(new TrackViews.Stop(representative.gtfsId(), representative.name(), representative.lat(),
                        representative.lon(), representative.mode(), distance, ids.arrivalStopId(), ids.arsId(),
                        null, null, null));
            } else {
                for (TrackRealtimeIdResolver.SubwayStationIds ids : realtimeIds.subway(stop, near.routeShortNames())) {
                    views.add(new TrackViews.Stop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), stop.mode(),
                            near.distanceMeters(), null, null, ids.arrivalStationId(), ids.lineId(), ids.lineName()));
                }
            }
        }
        return views;
    }

    /**
     * 피드별로 쪼개진 같은 버스 정류장 묶음(#326 앞단). 정류장 id → 그 묶음(대표가 맨 앞). 묶이지 않으면 없다.
     * 대표는 {@link TrackStopSiblings#representativeRank}, 대표에서 {@link TrackStopSiblings#MAX_DISTANCE_METERS} 밖이면 따로 둔다.
     * 근처 결과 안에서만 묶는다 — OTP 를 더 부르지 않는다.
     */
    static Map<String, List<NearStop>> siblingGroups(List<NearStop> stops) {
        Map<String, List<NearStop>> byKey = new LinkedHashMap<>();
        for (NearStop near : stops) {
            String key = near.stop().mode() == RoutingMode.BUS ? TrackStopSiblings.groupKey(near.stop().gtfsId()) : null;
            if (key != null) {
                byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(near);
            }
        }
        Map<String, List<NearStop>> groups = new HashMap<>();
        for (List<NearStop> members : byKey.values()) {
            if (members.size() < 2) {
                continue;
            }
            List<NearStop> sorted = new ArrayList<>(members);
            sorted.sort(Comparator.comparingInt(n -> TrackStopSiblings.representativeRank(n.stop().gtfsId())));
            TransitStop representative = sorted.get(0).stop();
            Set<String> close = new HashSet<>(TrackStopSiblings.within(representative,
                    sorted.stream().map(NearStop::stop).toList()));
            List<NearStop> group = sorted.stream()
                    .filter(n -> n.stop() == representative || close.contains(n.stop().gtfsId()))
                    .toList();
            if (group.size() > 1) {
                group.forEach(n -> groups.put(n.stop().gtfsId(), group));
            }
        }
        return groups;
    }

    /**
     * 정류장에서 탈 수 있는 노선·방향. 노선 이름 순.
     *
     * <p>피드별로 쪼개진 같은 정류장({@link TrackStopSiblings})의 노선까지 합친다 — 신분당선강남역 서울 stop 만 보면 경기
     * 노선 35개가 빠진다. 같은 노선 이름·방향이 둘이면 고른 정류장 쪽을 남긴다.
     */
    public List<TrackViews.Direction> directions(String gtfsStopId) {
        requireEnabled();
        Map<String, List<TransitPattern>> patternsByStop = source.withPermit(deadline -> {
            Map<String, List<TransitPattern>> byStop = new LinkedHashMap<>();
            byStop.put(gtfsStopId, source.patternsAtStop(gtfsStopId, deadline));
            for (String sibling : source.siblings(gtfsStopId, deadline)) {
                byStop.put(sibling, source.patternsAtStop(sibling, deadline));
            }
            return byStop;
        });
        List<TrackViews.Direction> directions = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, List<TransitPattern>> entry : patternsByStop.entrySet()) {
            String boardStopId = entry.getKey();
            String arrivalStopId = null;
            Set<String> seenHere = new HashSet<>();
            for (TrackPatternSelector.Branch branch : TrackPatternSelector.branches(boardStopId, entry.getValue())) {
                TransitPattern pattern = branch.pattern();
                if (pattern.route().mode() == RoutingMode.UNSUPPORTED || pattern.route().mode() == RoutingMode.WALK) {
                    continue;
                }
                String key = pattern.route().mode() + "|" + pattern.route().shortName() + "|" + branch.directionLabel();
                if (seen.contains(key)) {
                    continue; // 앞 정류장(고른 쪽 먼저)에서 이미 나온 노선·방향
                }
                seenHere.add(key);
                boolean bus = pattern.route().mode() == RoutingMode.BUS;
                if (bus && arrivalStopId == null) {
                    arrivalStopId = realtimeIds.busStop(boardStopId).arrivalStopId();
                }
                directions.add(new TrackViews.Direction(pattern.route().gtfsId(), pattern.route().mode(),
                        pattern.route().shortName(), color(pattern.route(), bus), branch.directionLabel(),
                        branch.terminalName(), pattern.code(),
                        bus ? realtimeIds.busRoute(pattern.route().gtfsId()) : null,
                        boardStopId, bus ? arrivalStopId : null));
            }
            seen.addAll(seenHere);
        }
        directions.sort((a, b) -> compareNullable(a.routeName(), b.routeName()));
        return directions;
    }

    /**
     * 그 노선·방향으로 {@code fromStopId} 다음부터 갈 수 있는 정류장(한 바퀴 안). 패턴 코드가 낡았으면 노선으로 다시 찾는다.
     * 내릴 수 없는 정류장(통과·승차 전용)은 빠진다 — {@code index} 는 그대로라 빈 번호가 생긴다.
     * 그 노선이 {@code fromStopId} 가 아니라 형제 정류장을 지나면 그쪽에서 탄다({@link TrackPatternSource#boardingStop}) —
     * 구버전 앱은 노선의 {@code boardStopGtfsId} 대신 고른 정류장 id 를 넘긴다.
     *
     * @throws BusinessException 422 {@code TRACK_STOPS_INVALID} — 그 노선이 그 정류장을 안 지나거나, 거기서 탈 수 없다
     */
    public List<TrackViews.AlightStop> alightStops(String routeGtfsId, String requestedFromStopId,
                                                   String patternCode) {
        requireEnabled();
        return source.withPermit(deadline -> {
            String fromStopId = source.boardingStop(routeGtfsId, requestedFromStopId, deadline);
            List<TransitPattern> patterns = source.patternsAtStop(fromStopId, deadline);
            TrackPatternSelector.Branch branch = TrackPatternSelector
                    .branchFor(routeGtfsId, fromStopId, patternCode, patterns)
                    .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID));
            Optional<TransitPatternDetail> detail = source.pattern(branch.pattern().code(), deadline);
            if (detail.isEmpty()) {
                throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
            }
            TrackPatternSelector.Lap lap = TrackPatternSelector.lap(branch.pattern(), fromStopId);
            List<TransitStop> stops = detail.get().stops();
            List<TransitStopTime> access = source.stopAccess(branch.pattern(), deadline).orElse(List.of());
            if (!access.isEmpty() && !access.get(lap.boardIndex()).pickup()) {
                throw new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID);
            }
            List<TrackViews.AlightStop> result = new ArrayList<>();
            for (int k = 0; k < lap.remaining().size(); k++) {
                int index = lap.boardIndex() + 1 + k;
                if (index >= stops.size()) {
                    break;
                }
                if (!access.isEmpty() && !access.get(index).dropoff()) {
                    continue;
                }
                TransitStop stop = stops.get(index);
                result.add(new TrackViews.AlightStop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), k));
            }
            return result;
        });
    }

    /**
     * 하차 선택 지도의 노선 선(#312). 추적 시작({@code TrackJourneyStartService})과 같은 패턴·같은 slice
     * ({@link TrackLegFactory#geometry}) 라 고를 때 본 선과 시작 후 선이 같다.
     *
     * <p>{@code toStopId} 가 있으면 시작과 같은 규칙({@link TrackPatternSelector#select})으로 패턴을 고르고 승차~하차를 자른다.
     * 없으면 하차 후보 조회와 같은 방향({@link TrackPatternSelector#branchFor})의 한 바퀴 끝(마지막 하차 후보)까지다.
     * 형상을 못 자르면 그 구간 정류장을 이은 직선({@code STOP_LINE}).
     *
     * @throws BusinessException 422 {@code TRACK_STOPS_INVALID} — 그 노선으로 그 승하차가 안 된다
     */
    public TrackViews.Shape shape(String routeGtfsId, String requestedFromStopId, String toStopId,
                                  String patternCode) {
        requireEnabled();
        return source.withPermit(deadline -> {
            String fromStopId = source.boardingStop(routeGtfsId, requestedFromStopId, deadline);
            List<TransitPattern> patterns = source.patternsAtStop(fromStopId, deadline);
            TransitPattern pattern;
            int boardIndex;
            int alightIndex;
            if (toStopId != null) {
                TrackPatternSelector.Selection selection = TrackPatternSelector
                        .select(routeGtfsId, fromStopId, toStopId, patternCode, patterns)
                        .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID));
                pattern = selection.pattern();
                boardIndex = selection.boardIndex();
                alightIndex = selection.alightIndex();
            } else {
                TrackPatternSelector.Branch branch = TrackPatternSelector
                        .branchFor(routeGtfsId, fromStopId, patternCode, patterns)
                        .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_STOPS_INVALID));
                pattern = branch.pattern();
                TrackPatternSelector.Lap lap = TrackPatternSelector.lap(pattern, fromStopId);
                boardIndex = lap.boardIndex();
                alightIndex = Math.min(lap.boardIndex() + lap.remaining().size(), pattern.stops().size() - 1);
            }
            TransitPatternDetail detail = detail(pattern, deadline);
            return shapeOf(detail, boardIndex, alightIndex, source.graphVersion());
        });
    }

    private TransitPatternDetail detail(TransitPattern pattern, RoutingDeadline deadline) {
        TransitPatternDetail detail = source.pattern(pattern.code(), deadline)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE));
        if (detail.stops().size() != pattern.stops().size()) {
            // 정류장 패턴(캐시)과 상세가 다른 그래프에서 왔다. 섞어 쓰면 위치가 어긋난다(시작과 같은 거절).
            throw new BusinessException(JourneyErrorCode.TRACK_PATTERN_UNAVAILABLE);
        }
        return detail;
    }

    static TrackViews.Shape shapeOf(TransitPatternDetail detail, int boardIndex, int alightIndex,
                                    String graphVersion) {
        LegGeometry geometry = TrackLegFactory.geometry(detail, boardIndex, alightIndex, graphVersion);
        if (geometry.quality() == GeometryQuality.SHAPE_OK) {
            return new TrackViews.Shape(EncodedPolyline.decode(geometry.displayPoints()),
                    TrackViews.ShapeQuality.SHAPE_OK);
        }
        List<CoordPoint> line = detail.stops().subList(boardIndex, alightIndex + 1).stream()
                .map(stop -> new CoordPoint(stop.lon(), stop.lat()))
                .toList();
        return new TrackViews.Shape(line, TrackViews.ShapeQuality.STOP_LINE);
    }

    private void requireEnabled() {
        if (!properties.isEnabled()) {
            throw new BusinessException(CommonErrorCode.NOT_FOUND);
        }
    }

    private static boolean matches(RoutingMode wanted, RoutingMode actual) {
        if (wanted == null) {
            return actual != RoutingMode.UNSUPPORTED;
        }
        if (wanted == RoutingMode.SUBWAY) {
            return actual == RoutingMode.SUBWAY || actual == RoutingMode.RAIL;
        }
        return wanted == actual;
    }

    /** 그래프 색을 쓰되, 경기 버스는 노선 유형 색이 먼저다(#391 — 검색 응답과 같은 규칙). */
    private String color(TransitRoute route, boolean bus) {
        if (bus) {
            Optional<String> gyeonggi = gyeonggiColors.color(route.gtfsId());
            if (gyeonggi.isPresent()) {
                return gyeonggi.get();
            }
        }
        return route.color() == null ? null : "#" + route.color();
    }

    private static int compareNullable(String a, String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareTo(b);
    }
}
