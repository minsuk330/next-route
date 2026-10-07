package watoo.grd.nextroute.application.journey.service;

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
public final class TrackLegFactory {

    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    /** 정류장마다 가까운 형상점을 찾을 때 앞으로 볼 점 수. 정류장 사이 형상점은 실측 수십 개 수준이다. */
    private static final int GEOMETRY_LOOKAHEAD = 600;

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
        String direction = SubwayDirectionNormalizer.labelFrom(route.longName());

        Set<MissingAttribute> missing = EnumSet.of(MissingAttribute.REALTIME_ID);
        if (direction == null) {
            missing.add(MissingAttribute.DIRECTION);
        }
        if (route.color() == null) {
            missing.add(MissingAttribute.COLOR);
        }

        return new RoutingLeg(route.mode(), route.otpMode(), feedId, route.agencyGtfsId(),
                routeId, route.shortName(), direction, tripId, serviceDate,
                midnight.plusSeconds(departSec), midnight.plusSeconds(arriveSec), duration,
                occurrences, List.of(),
                geometry(detail, boardIndex, alightIndex, graphVersion),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), missing, route.color());
    }

    /**
     * 패턴 형상에서 승차~하차를 잘라 낸다. 정류장마다 앞 정류장 자리 뒤에서 가장 가까운 형상점을 잡는다(여러 바퀴 패턴에서
     * 다른 바퀴의 같은 자리로 튀지 않게). 못 자르면 형상 없음 — 앱은 정류장 직선으로 그린다(검색 결과와 같은 폴백).
     */
    static LegGeometry geometry(TransitPatternDetail detail, int boardIndex, int alightIndex, String graphVersion) {
        List<CoordPoint> points = EncodedPolyline.decode(detail.geometryPoints());
        Optional<int[]> range = sliceRange(points, detail.stops(), boardIndex, alightIndex);
        if (range.isEmpty()) {
            return LegGeometry.missing("pattern geometry unavailable");
        }
        List<CoordPoint> slice = points.subList(range.get()[0], range.get()[1] + 1);
        if (slice.size() < 2) {
            return LegGeometry.missing("pattern geometry too short");
        }
        String encoded = encode(slice);
        return new LegGeometry(GeometrySource.GTFS_SHAPE, GeometryQuality.SHAPE_OK,
                "pfaedle gtfs shape (pattern slice)", graphVersion, encoded, encoded);
    }

    static Optional<int[]> sliceRange(List<CoordPoint> points, List<TransitStop> stops,
                                      int boardIndex, int alightIndex) {
        if (points.size() < 2) {
            return Optional.empty();
        }
        int cursor = 0;
        int boardPoint = -1;
        for (int s = 0; s <= alightIndex; s++) {
            TransitStop stop = stops.get(s);
            int best = cursor;
            double bestDist = Double.MAX_VALUE;
            int limit = Math.min(points.size(), cursor + GEOMETRY_LOOKAHEAD);
            for (int p = cursor; p < limit; p++) {
                double d = squaredDistance(points.get(p), stop);
                if (d < bestDist) {
                    bestDist = d;
                    best = p;
                }
            }
            cursor = best;
            if (s == boardIndex) {
                boardPoint = best;
            }
        }
        if (boardPoint < 0 || cursor <= boardPoint) {
            return Optional.empty();
        }
        return Optional.of(new int[]{boardPoint, cursor});
    }

    private static double squaredDistance(CoordPoint point, TransitStop stop) {
        double dx = (point.x() - stop.lon()) * Math.cos(Math.toRadians(stop.lat()));
        double dy = point.y() - stop.lat();
        return dx * dx + dy * dy;
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
