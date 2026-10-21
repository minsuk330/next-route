package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitPatternDetail;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitRoute;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStopTime;
import watoo.grd.nextroute.application.route.routing.EncodedPolyline;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.GeometrySource;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.MissingAttribute;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 패턴 구간 하나를 <b>OTP 검색 결과와 같은 모양의</b> {@link RoutingLeg} 로 만든다(#265 단독 추적). 순수 함수다.
 *
 * <p>같은 모양이어야 하는 이유: 뒤이어 {@code LegacyRouteResponseMapper.toSubPath} 가 이 구간에서 서울·경기·TAGO 실시간
 * id 와 지하철 방향({@code wayCode})을 채운다. 직접 {@code SubPathResult} 를 조립하면 그 보강이 빠져 지하철 도착 후보에
 * 반대 방향 열차가 섞인다. 그래서 {@code OtpPlanConnectionMapper.toLeg} 와 같은 칸을 같은 규칙으로 채운다.
 *
 * <p><b>소요는 시간표에서만 온다.</b> 정차 시각이 없거나 패턴과 어긋나면 실패다 — 근사하지 않는다. 소요는 지하철 오프라인
 * 보호 시간에도 들어가, 틀리면 여정이 일찍 접힌다.
 */
@Slf4j
public final class TrackLegFactory {

    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    static final String SLICE_FAILED_METRIC = "track.geometry.slice.failed";

    private static final double METERS_PER_DEGREE_LAT = 110_540.0;
    private static final double METERS_PER_DEGREE_LON_AT_EQUATOR = 111_320.0;

    private TrackLegFactory() {
    }

    /** 시간표가 없거나 패턴과 안 맞는다. */
    public static final class ScheduleUnavailableException extends RuntimeException {
        public ScheduleUnavailableException(String message) {
            super(message);
        }
    }

    /**
     * @param stopTimes   그 패턴 trip 하나의 정차 시각(패턴 정류장과 같은 순서·개수)
     * @param tripId      그 trip id
     * @param serviceDate 그 trip 의 운행일
     * @param graphVersion 형상 버전 표기
     * @throws ScheduleUnavailableException 시각이 없거나 패턴과 어긋나거나 소요가 0 이하
     */
    public static RoutingLeg build(TransitPatternDetail detail, int boardIndex, int alightIndex,
                                   List<TransitStopTime> stopTimes, String tripId, LocalDate serviceDate,
                                   String graphVersion) {
        List<TransitStop> stops = detail.stops();
        if (boardIndex < 0 || alightIndex <= boardIndex || alightIndex >= stops.size()) {
            throw new IllegalArgumentException("승하차 위치가 패턴 밖이다");
        }
        if (stopTimes.size() != stops.size()
                || !stops.get(boardIndex).gtfsId().equals(stopTimes.get(boardIndex).stopGtfsId())
                || !stops.get(alightIndex).gtfsId().equals(stopTimes.get(alightIndex).stopGtfsId())) {
            throw new ScheduleUnavailableException("정차 시각이 패턴 정류장과 맞지 않는다");
        }
        int departSec = stopTimes.get(boardIndex).scheduledDeparture();
        int arriveSec = stopTimes.get(alightIndex).scheduledArrival();
        int duration = arriveSec - departSec;
        if (duration <= 0) {
            throw new ScheduleUnavailableException("소요가 0 이하다");
        }

        Instant midnight = serviceDate.atStartOfDay(FEED_ZONE).toInstant();
        List<StopOccurrence> occurrences = new ArrayList<>();
        for (int i = boardIndex; i <= alightIndex; i++) {
            TransitStop stop = stops.get(i);
            TransitStopTime time = stopTimes.get(i);
            occurrences.add(new StopOccurrence(stop.gtfsId(), detail.code(), null, i - boardIndex,
                    stop.lon(), stop.lat(), stop.name(),
                    midnight.plusSeconds(time.scheduledArrival()),
                    midnight.plusSeconds(time.scheduledDeparture())));
        }

        TransitRoute route = detail.route();
        String routeId = route.gtfsId();
        String feedId = routeId != null && routeId.contains(":") ? routeId.substring(0, routeId.indexOf(':')) : null;
        String direction = SubwayDirectionNormalizer.labelFor(routeId, route.longName(),
                occurrences.get(0).sourceStopId(), occurrences.get(occurrences.size() - 1).sourceStopId());

        Set<MissingAttribute> missing = EnumSet.of(MissingAttribute.REALTIME_ID);
        if (direction == null) {
            missing.add(MissingAttribute.DIRECTION);
        }
        if (route.color() == null) {
            missing.add(MissingAttribute.COLOR);
        }

        // 열차 종류는 여기서 판정하지 않는다(OTP 매퍼와 같다). 단독 추적은 어댑터를 안 거치므로 부르는 쪽
        // (TrackJourneyStartService)이 RailServiceClassifier 로 직접 붙인다(subway-express S2).
        return new RoutingLeg(route.mode(), route.otpMode(), feedId, route.agencyGtfsId(),
                routeId, route.shortName(), direction, tripId, serviceDate,
                midnight.plusSeconds(departSec), midnight.plusSeconds(arriveSec), duration,
                occurrences, List.of(),
                geometry(detail, boardIndex, alightIndex, graphVersion),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), missing, route.color(), null,
                route.longName(), detail.code(), null);
    }

    /**
     * 패턴 형상에서 승차~하차를 잘라 낸다. 못 자르면 형상 없음 — 앱은 정류장 직선으로 그린다(검색 결과와 같은 폴백).
     * 실패는 패턴·승하차 순번·이유를 {@code warn} 으로 남기고 {@value #SLICE_FAILED_METRIC} 를 센다.
     */
    static LegGeometry geometry(TransitPatternDetail detail, int boardIndex, int alightIndex, String graphVersion) {
        List<CoordPoint> points = EncodedPolyline.decode(detail.geometryPoints());
        Optional<List<CoordPoint>> slice = slice(points, detail.stops(), boardIndex, alightIndex);
        if (slice.isEmpty()) {
            String reason = points.size() < 2 ? "NO_GEOMETRY" : "NOT_ORDERED";
            log.warn("[Track] 형상 자르기 실패 pattern={} board={} alight={} points={} reason={}",
                    detail.code(), boardIndex, alightIndex, points.size(), reason);
            Metrics.counter(SLICE_FAILED_METRIC, "reason", reason).increment();
            return LegGeometry.missing("pattern geometry unavailable");
        }
        String encoded = encode(slice.get());
        return new LegGeometry(GeometrySource.GTFS_SHAPE, GeometryQuality.SHAPE_OK,
                "pfaedle gtfs shape (pattern slice)", graphVersion, encoded, encoded);
    }

    /**
     * 승차~하차 선. 시작·끝은 두 정류장을 선분에 내린 수선의 발이고 그 사이는 원래 꼭짓점이다.
     *
     * <p>패턴 첫 정류장부터 하차까지 모든 정류장을 <b>순서를 지키는 배치 중 거리 합이 최소인 것</b>으로 선 위에 놓는다
     * ({@link BusShapeStopSplitter} 와 같은 방식). 정류장마다 가장 가까운 점을 앞에서부터 탐욕으로 고르면, 가는 길과 오는
     * 길이 겹치는 왕복 노선(040번)에서 한 정류장이 오는 길 점에 걸리는 순간 커서가 그쪽으로 뛰고 뒤 정류장이 모두 끝에
     * 몰려 승차와 하차가 같은 점이 된다(#361). 거리는 꼭짓점이 아니라 선분까지 잰다 — 꼭짓점이 듬성한 구간에서 엉뚱한
     * 바퀴의 꼭짓점이 더 가까워 보이는 것을 막는다.
     */
    static Optional<List<CoordPoint>> slice(List<CoordPoint> points, List<TransitStop> stops,
                                            int boardIndex, int alightIndex) {
        if (points.size() < 2) {
            return Optional.empty();
        }
        Cut[] cuts = place(points, stops.subList(0, alightIndex + 1));
        Cut board = cuts[boardIndex];
        Cut alight = cuts[alightIndex];
        if (alight.edge < board.edge || (alight.edge == board.edge && alight.t <= board.t)) {
            return Optional.empty();
        }
        List<CoordPoint> line = new ArrayList<>();
        line.add(board.point);
        for (int v = board.edge + 1; v <= alight.edge; v++) {
            add(line, points.get(v));
        }
        add(line, alight.point);
        return line.size() < 2 ? Optional.empty() : Optional.of(line);
    }

    /**
     * 정류장마다 선 위 지점을 고른다. 뒤 정류장은 앞 정류장보다 앞 선분에 오지 않고, 같은 선분이면 비율이 작지 않다.
     * 행마다 비용 두 줄만 들고 역추적용 출처만 전부 남긴다(정류장 수 × 선분 수 int).
     */
    private static Cut[] place(List<CoordPoint> points, List<TransitStop> stops) {
        int edges = points.size() - 1;
        int n = stops.size();
        int[][] from = new int[n][edges];
        double[] prevCost = new double[edges];
        double[] prevT = new double[edges];
        double[] cost = new double[edges];
        double[] t = new double[edges];
        for (int k = 0; k < n; k++) {
            TransitStop stop = stops.get(k);
            double earlierMin = Double.POSITIVE_INFINITY;
            int earlierArg = -1;
            for (int i = 0; i < edges; i++) {
                Cut cut = project(stop, points, i);
                t[i] = cut.t;
                if (k == 0) {
                    cost[i] = cut.distanceMeters;
                    continue;
                }
                double previous = earlierMin;
                int previousArg = earlierArg;
                if (prevT[i] <= cut.t && prevCost[i] < previous) {
                    previous = prevCost[i];
                    previousArg = i;
                }
                cost[i] = cut.distanceMeters + previous;
                from[k][i] = previousArg;
                if (prevCost[i] < earlierMin) {
                    earlierMin = prevCost[i];
                    earlierArg = i;
                }
            }
            double[] swap = prevCost;
            prevCost = cost;
            cost = swap;
            swap = prevT;
            prevT = t;
            t = swap;
        }

        int arg = 0;
        for (int i = 1; i < edges; i++) {
            if (prevCost[i] < prevCost[arg]) {
                arg = i;
            }
        }
        Cut[] chosen = new Cut[n];
        for (int k = n - 1; k >= 0; k--) {
            chosen[k] = project(stops.get(k), points, Math.max(arg, 0));
            arg = k > 0 ? from[k][Math.max(arg, 0)] : arg;
        }
        return chosen;
    }

    /** 정류장을 선분 {@code edge} 위에 수선의 발로 내린다. 정류장 위도 기준 평면 근사(수백 m 규모). */
    private static Cut project(TransitStop stop, List<CoordPoint> points, int edge) {
        CoordPoint a = points.get(edge);
        CoordPoint b = points.get(edge + 1);
        double lonScale = METERS_PER_DEGREE_LON_AT_EQUATOR * Math.cos(Math.toRadians(stop.lat()));
        double ax = (a.x() - stop.lon()) * lonScale;
        double ay = (a.y() - stop.lat()) * METERS_PER_DEGREE_LAT;
        double bx = (b.x() - stop.lon()) * lonScale;
        double by = (b.y() - stop.lat()) * METERS_PER_DEGREE_LAT;
        double dx = bx - ax;
        double dy = by - ay;
        double lengthSquared = dx * dx + dy * dy;
        double t = lengthSquared == 0 ? 0.0 : Math.max(0.0, Math.min(1.0, -(ax * dx + ay * dy) / lengthSquared));
        CoordPoint point = t == 0.0 ? a : t == 1.0 ? b
                : new CoordPoint(a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
        return new Cut(edge, t, point, Math.hypot(ax + t * dx, ay + t * dy));
    }

    private static void add(List<CoordPoint> line, CoordPoint point) {
        CoordPoint last = line.get(line.size() - 1);
        if (Double.compare(last.x(), point.x()) != 0 || Double.compare(last.y(), point.y()) != 0) {
            line.add(point);
        }
    }

    /** 선 위 지점. {@code edge} 번째 선분의 {@code t} 비율 위치다. */
    private record Cut(int edge, double t, CoordPoint point, double distanceMeters) {
    }

    /** Google encoded polyline(precision 5, 위도·경도 순). {@link EncodedPolyline#decode} 의 역. */
    static String encode(List<CoordPoint> points) {
        StringBuilder out = new StringBuilder();
        long prevLat = 0;
        long prevLon = 0;
        for (CoordPoint point : points) {
            long lat = Math.round(point.y() * 1e5);
            long lon = Math.round(point.x() * 1e5);
            appendSigned(out, lat - prevLat);
            appendSigned(out, lon - prevLon);
            prevLat = lat;
            prevLon = lon;
        }
        return out.toString();
    }

    private static void appendSigned(StringBuilder out, long value) {
        long v = value < 0 ? ~(value << 1) : value << 1;
        while (v >= 0x20) {
            out.append((char) ((0x20 | (v & 0x1f)) + 63));
            v >>= 5;
        }
        out.append((char) (v + 63));
    }
}
