package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneyTrackProperties;
import watoo.grd.nextroute.application.journey.dto.TrackViews;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPattern;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.NearStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.CommonErrorCode;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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

    /**
     * @param mode {@code BUS} 또는 {@code SUBWAY}(철도·경전철 포함). null 이면 전부
     */
    public List<TrackViews.Stop> stopsNear(double lat, double lon, RoutingMode mode) {
        requireEnabled();
        List<NearStop> stops = source.withPermit(deadline -> source.port().stopsNear(lat, lon,
                properties.getNearRadiusMeters(), properties.getNearLimit(), deadline));
        List<TrackViews.Stop> views = new ArrayList<>();
        for (NearStop near : stops) {
            TransitStop stop = near.stop();
            if (!matches(mode, stop.mode())) {
                continue;
            }
            if (stop.mode() == RoutingMode.BUS) {
                TrackRealtimeIdResolver.BusStopIds ids = realtimeIds.busStop(stop.gtfsId());
                views.add(new TrackViews.Stop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), stop.mode(),
                        near.distanceMeters(), ids.arrivalStopId(), ids.arsId(), null, null, null));
            } else {
                for (TrackRealtimeIdResolver.SubwayStationIds ids : realtimeIds.subway(stop, near.routeShortNames())) {
                    views.add(new TrackViews.Stop(stop.gtfsId(), stop.name(), stop.lat(), stop.lon(), stop.mode(),
                            near.distanceMeters(), null, null, ids.arrivalStationId(), ids.lineId(), ids.lineName()));
                }
            }
        }
        return views;
    }

    /** 정류장에서 탈 수 있는 노선·방향. 노선 이름 순. */
    public List<TrackViews.Direction> directions(String gtfsStopId) {
        requireEnabled();
        List<TransitPattern> patterns = source.withPermit(deadline -> source.patternsAtStop(gtfsStopId, deadline));
        List<TrackViews.Direction> directions = new ArrayList<>();
        for (TrackPatternSelector.Branch branch : TrackPatternSelector.branches(gtfsStopId, patterns)) {
            TransitPattern pattern = branch.pattern();
            if (pattern.route().mode() == RoutingMode.UNSUPPORTED || pattern.route().mode() == RoutingMode.WALK) {
                continue;
            }
            String arrivalRouteId = pattern.route().mode() == RoutingMode.BUS
                    ? realtimeIds.busRoute(pattern.route().gtfsId()) : null;
            directions.add(new TrackViews.Direction(pattern.route().gtfsId(), pattern.route().mode(),
                    pattern.route().shortName(), color(pattern.route().color()), branch.directionLabel(),
                    branch.terminalName(), pattern.code(), arrivalRouteId));
        }
        directions.sort((a, b) -> compareNullable(a.routeName(), b.routeName()));
        return directions;
    }

    /**
     * 그 노선·방향으로 {@code fromStopId} 다음부터 갈 수 있는 정류장(한 바퀴 안). 패턴 코드가 낡았으면 노선으로 다시 찾는다.
     *
     * @throws BusinessException 422 {@code TRACK_STOPS_INVALID} — 그 노선이 그 정류장을 안 지난다
     */
    public List<TrackViews.AlightStop> alightStops(String routeGtfsId, String fromStopId, String patternCode) {
        requireEnabled();
        return source.withPermit(deadline -> {
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
            List<TrackViews.AlightStop> result = new ArrayList<>();
            for (int k = 0; k < lap.remaining().size(); k++) {
                int index = lap.boardIndex() + 1 + k;
                if (index >= stops.size()) {
                    break;
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
    public TrackViews.Shape shape(String routeGtfsId, String fromStopId, String toStopId, String patternCode) {
        requireEnabled();
        return source.withPermit(deadline -> {
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

    private static String color(String raw) {
        return raw == null ? null : "#" + raw;
    }

    private static int compareNullable(String a, String b) {
        if (a == null) {
            return b == null ? 0 : 1;
        }
        return b == null ? -1 : a.compareTo(b);
    }
}
