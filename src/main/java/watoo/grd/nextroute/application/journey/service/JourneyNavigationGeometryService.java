package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.bus.dto.BusRoutePolylineData;
import watoo.grd.nextroute.application.bus.dto.BusRouteSegmentPolylineSection;
import watoo.grd.nextroute.application.bus.port.out.BusRouteSegmentPolylinePort;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 여정 전체의 지도·진행 판정용 좌표를 모아 준다.
 *
 * <p>좌표 원천이 단계 종류마다 다르다.
 * <ul>
 *   <li>버스 타는 단계 — {@code bus_route_segment_polyline} 의 정류장 구간별 좌표</li>
 *   <li>지하철 타는 단계 — 시작 시 얼려 둔 검색 결과의 구간 좌표</li>
 *   <li>걷기 단계 — 같은 검색 결과의 TMAP 보행자 좌표</li>
 * </ul>
 *
 * <p>세 원천 모두 비어 있을 수 있다. 버스는 구간 품질이 불량할 수 있고, 지하철은 폴리라인 수집이
 * 아직 안 됐을 수 있고, 걷기는 검색이 바쁠 때 TMAP 호출을 건너뛴다. 그래서 <b>좌표가 없으면
 * 단계를 빼는 대신 직선으로 채우고 품질을 낮춘다.</b> 빼면 앱이 구간 수를 서버와 다르게 세고
 * 진행 순번이 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyNavigationGeometryService implements GetJourneyNavigationGeometryUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final BusRouteSegmentPolylinePort busPolylinePort;
    private final BusRouteStopRepository busRouteStopRepository;
    private final JourneyCompiler compiler;
    private final GeometrySegmentAllocator allocator;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public JourneyNavigationGeometry get(Long userId, Long journeyId, Integer requestedRevision) {
        JourneySession journey = sessionRepository
                .findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));

        int revision = journey.getRouteRevision();
        if (requestedRevision != null && requestedRevision != revision) {
            throw new StaleRouteRevisionException(revision);
        }

        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(journeyId, revision);
        Map<Long, List<JourneyLegRouteOption>> optionsByLeg = loadRouteOptions(legs);
        Snapshot snapshot = recompile(journey.getRouteSnapshot());

        List<LegGeometry> geometries = new ArrayList<>(legs.size());
        Map<String, List<RouteStopProjection>> routeStopsByRouteId = new HashMap<>();
        OffsetDateTime oldestFetchedAt = null;
        for (int i = 0; i < legs.size(); i++) {
            JourneyLeg leg = legs.get(i);
            SubPathResult sub = snapshot.subPathOf(leg.getLegIndex());
            Built built = switch (leg.getType()) {
                case RIDE_BUS -> busLeg(leg, ownerOfOptions(legs, i), optionsByLeg, sub,
                        routeStopsByRouteId);
                case WAIT_BUS, WAIT_SUBWAY -> new Built(waitLeg(leg, sub), null);
                default -> new Built(snapshotLeg(leg, sub), null);
            };
            geometries.add(built.geometry());
            oldestFetchedAt = older(oldestFetchedAt, built.sourceFetchedAt());
        }

        return new JourneyNavigationGeometry(journeyId, revision, geometries, oldestFetchedAt);
    }

    // ── 버스 ────────────────────────────────────────────────────────────────

    /**
     * 버스 타는 단계. 구간 좌표를 합치지 않고 구간 단위로 내려준다.
     *
     * <p>노선·정류장 순번을 아직 해석하지 못한 단계가 있다. 그때는 실측 좌표를 찾을 수 없으니
     * 검색 결과 좌표로, 그것도 없으면 직선으로 떨어진다.
     */
    private Built busLeg(JourneyLeg leg, JourneyLeg optionOwner,
                         Map<Long, List<JourneyLegRouteOption>> optionsByLeg,
                         SubPathResult sub,
                         Map<String, List<RouteStopProjection>> routeStopsByRouteId) {
        JourneyLegRouteOption option = selectedOption(leg, optionOwner, optionsByLeg);
        if (!hasBusScope(option)) {
            log.debug("버스 구간 좌표 조회 불가 — legIndex={}, 노선·순번 미해석", leg.getLegIndex());
            return new Built(snapshotLeg(leg, sub), null);
        }

        List<StopGeometry> stops = routeStopsByRouteId
                .computeIfAbsent(option.getRouteId(), busRouteStopRepository::findStopsByRouteId)
                .stream()
                .filter(stop -> stop.getSeq() != null
                        && stop.getSeq() >= option.getBoardingSeq()
                        && stop.getSeq() <= option.getAlightingSeq())
                .sorted(Comparator.comparingInt(RouteStopProjection::getSeq))
                .map(stop -> new StopGeometry(
                        stop.getSeq(),
                        stop.getStopId(),
                        stop.getStopName(),
                        stop.getLongitude(),
                        stop.getLatitude()))
                .toList();

        List<BusRouteSegmentPolylineSection> sections = busPolylinePort.findSections(
                option.getRouteId(), option.getBoardingSeq(), option.getAlightingSeq());
        if (sections.isEmpty()) {
            return new Built(withStops(snapshotLeg(leg, sub), stops), null);
        }

        List<SegmentGeometry> raw = new ArrayList<>(sections.size());
        OffsetDateTime oldest = null;
        for (BusRouteSegmentPolylineSection section : sections) {
            raw.add(new SegmentGeometry(
                    0, section.sectionId(), section.fromSeq(), section.toSeq(),
                    0.0, null,
                    quality(section), section.qualityReason(),
                    toGeoPoints(section.points())));
            oldest = older(oldest, section.sourceFetchedAt());
        }
        List<SegmentGeometry> segments = allocator.distribute(raw, leg.getPlannedDurationSec());
        return new Built(assemble(leg, option.getRouteId(), segments, stops), oldest);
    }

    private boolean hasBusScope(JourneyLegRouteOption option) {
        return option != null
                && option.getRouteId() != null && !option.getRouteId().isBlank()
                && option.getBoardingSeq() != null && option.getAlightingSeq() != null
                && option.getBoardingSeq() < option.getAlightingSeq();
    }

    private GeometryQuality quality(BusRouteSegmentPolylineSection section) {
        return switch (section.qualityStatus()) {
            case OK -> section.points().size() >= 2 ? GeometryQuality.OK : GeometryQuality.FAILED;
            case REVIEW -> GeometryQuality.REVIEW;
            case FAILED -> GeometryQuality.FAILED;
        };
    }

    // ── 검색 결과 좌표(지하철·걷기) ────────────────────────────────────────

    /**
     * 시작 시 얼려 둔 검색 결과 좌표를 쓴다. 지하철 구간 폴리라인과 TMAP 보행자 좌표가 같은
     * 자리에 들어 있다.
     *
     * <p>좌표가 없으면 출발→도착 직선으로 채우고 품질을 {@code APPROXIMATE} 로 낮춘다.
     */
    private LegGeometry snapshotLeg(JourneyLeg leg, SubPathResult sub) {
        List<GeoPoint> points = sub == null ? List.of() : toGeoPointsFromCoords(sub.polyline());
        if (points.size() >= 2) {
            List<SegmentGeometry> segments = allocator.allocate(points, leg.getPlannedDurationSec());
            return assemble(leg, leg.getRouteId(), segments);
        }
        return straightLine(leg, sub);
    }

    /**
     * 실측 좌표가 없을 때의 마지막 수단. 진행을 막지 않고 품질만 낮춘다.
     *
     * <p>양 끝 좌표는 저장된 단계가 아니라 얼려 둔 검색 결과에서 가져온다. {@code journey_leg}
     * 는 좌표를 보관하지 않는다 — 정밀 좌표를 서버에 남기지 않는다는 결정(O10) 때문이다.
     */
    private LegGeometry straightLine(JourneyLeg leg, SubPathResult sub) {
        if (sub == null || sub.startX() == null || sub.startY() == null
                || sub.endX() == null || sub.endY() == null) {
            return new LegGeometry(leg.getLegIndex(), leg.getType(), leg.getRouteId(),
                    leg.getPlannedDurationSec(), 0.0, GeometryQuality.FAILED, List.of(), List.of());
        }
        List<GeoPoint> points = List.of(
                new GeoPoint(sub.startX(), sub.startY()),
                new GeoPoint(sub.endX(), sub.endY()));
        List<SegmentGeometry> segments = withQuality(
                allocator.allocate(points, leg.getPlannedDurationSec()),
                GeometryQuality.APPROXIMATE, "NO_SOURCE_GEOMETRY");
        return assemble(leg, leg.getRouteId(), segments);
    }

    /** 기다리는 단계는 한 지점이다. 지도 표시에만 쓰고 진행 판정 구간을 만들지 않는다. */
    private LegGeometry waitLeg(JourneyLeg leg, SubPathResult sub) {
        List<GeoPoint> points = sub == null || sub.startX() == null || sub.startY() == null
                ? List.of()
                : List.of(new GeoPoint(sub.startX(), sub.startY()));
        return new LegGeometry(leg.getLegIndex(), leg.getType(), leg.getRouteId(),
                leg.getPlannedDurationSec(), 0.0,
                points.isEmpty() ? GeometryQuality.FAILED : GeometryQuality.OK,
                points, List.of());
    }

    // ── 조립 ────────────────────────────────────────────────────────────────

    /** 구간을 이어 붙여 지도용 전체 좌표를 만들고 단계 품질을 가장 나쁜 구간으로 정한다. */
    private LegGeometry assemble(JourneyLeg leg, String routeId, List<SegmentGeometry> segments) {
        return assemble(leg, routeId, segments, List.of());
    }

    private LegGeometry assemble(JourneyLeg leg, String routeId, List<SegmentGeometry> segments,
                                 List<StopGeometry> stops) {
        List<GeoPoint> merged = new ArrayList<>();
        double totalMeters = 0.0;
        GeometryQuality worst = GeometryQuality.OK;
        for (SegmentGeometry segment : segments) {
            totalMeters += segment.distanceMeters();
            worst = worse(worst, segment.quality());
            for (GeoPoint point : segment.points()) {
                if (merged.isEmpty() || !samePoint(merged.get(merged.size() - 1), point)) {
                    merged.add(point);
                }
            }
        }
        return new LegGeometry(leg.getLegIndex(), leg.getType(), routeId,
                leg.getPlannedDurationSec(), Math.round(totalMeters * 10.0) / 10.0,
                segments.isEmpty() ? GeometryQuality.FAILED : worst, merged, segments, stops);
    }

    private LegGeometry withStops(LegGeometry geometry, List<StopGeometry> stops) {
        return new LegGeometry(
                geometry.legIndex(), geometry.type(), geometry.routeId(),
                geometry.plannedDurationSeconds(), geometry.totalDistanceMeters(),
                geometry.quality(), geometry.points(), geometry.segments(), stops);
    }

    /** 나쁜 쪽을 남긴다. 앱이 단계 하나만 보고 "믿어도 되는가"를 판단할 수 있어야 한다. */
    private GeometryQuality worse(GeometryQuality a, GeometryQuality b) {
        return rank(b) > rank(a) ? b : a;
    }

    private int rank(GeometryQuality quality) {
        return switch (quality) {
            case OK -> 0;
            case REVIEW -> 1;
            case APPROXIMATE -> 2;
            case FAILED -> 3;
        };
    }

    private List<SegmentGeometry> withQuality(List<SegmentGeometry> segments,
                                              GeometryQuality quality, String reason) {
        return segments.stream().map(segment -> segment.withQuality(quality, reason)).toList();
    }

    // ── 원천 되찾기 ─────────────────────────────────────────────────────────

    /**
     * 얼려 둔 검색 결과를 같은 규칙으로 다시 쪼개 단계 → 원본 구간 순번을 얻는다.
     *
     * <p>실패해도 예외를 던지지 않는다. 좌표를 못 찾는 것은 직선 대체로 살아남을 수 있는 일이고,
     * 여기서 막으면 지도를 아예 못 그린다.
     */
    private Snapshot recompile(JsonNode routeSnapshot) {
        if (routeSnapshot == null || routeSnapshot.isNull()) {
            return Snapshot.empty();
        }
        try {
            PathResult path = objectMapper.treeToValue(routeSnapshot, PathResult.class);
            return new Snapshot(path, compiler.compileDetailed(path));
        } catch (Exception e) {
            log.warn("route snapshot 재쪼개기 실패 — 직선 좌표로 내려간다", e);
            return Snapshot.empty();
        }
    }

    // ── 보조 ────────────────────────────────────────────────────────────────

    private Map<Long, List<JourneyLegRouteOption>> loadRouteOptions(List<JourneyLeg> legs) {
        List<Long> legIds = legs.stream().map(JourneyLeg::getId).toList();
        if (legIds.isEmpty()) {
            return Map.of();
        }
        return routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(legIds).stream()
                .collect(Collectors.groupingBy(JourneyLegRouteOption::getJourneyLegId));
    }

    /**
     * 후보를 들고 있는 단계. 쪼개기가 기다리기 → 타고가기 순서로 붙이므로 바로 앞 단계다.
     *
     * <p>{@code journey_leg} 에 짝을 가리키는 컬럼이 없어 순서 규칙으로 찾는다.
     * {@code JourneyStateMachine} 도 같은 규칙으로 짝을 찾는다.
     */
    private JourneyLeg ownerOfOptions(List<JourneyLeg> legs, int rideIndex) {
        if (rideIndex <= 0) {
            return null;
        }
        JourneyLeg previous = legs.get(rideIndex - 1);
        return previous.getType().isWaiting() ? previous : null;
    }

    /**
     * 타는 단계가 쓸 노선 후보. 후보는 짝이 되는 기다리는 단계가 들고 있다.
     *
     * <p>선택된 후보가 있으면 그것을, 없으면 첫 후보를 쓴다. Phase 3 에는 사용자 선택이 없으니
     * 사실상 첫 후보다.
     */
    private JourneyLegRouteOption selectedOption(JourneyLeg leg, JourneyLeg optionOwner,
                                                 Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        List<JourneyLegRouteOption> own = optionsByLeg.getOrDefault(leg.getId(), List.of());
        List<JourneyLegRouteOption> candidates = own.isEmpty() && optionOwner != null
                ? optionsByLeg.getOrDefault(optionOwner.getId(), List.of())
                : own;
        if (candidates.isEmpty()) {
            return null;
        }
        Long selected = leg.getSelectedRouteOptionId();
        if (selected != null) {
            return candidates.stream()
                    .filter(option -> selected.equals(option.getId()))
                    .findFirst()
                    .orElseGet(() -> firstOption(candidates));
        }
        return firstOption(candidates);
    }

    private JourneyLegRouteOption firstOption(List<JourneyLegRouteOption> candidates) {
        return candidates.stream()
                .min(Comparator.comparingInt(JourneyLegRouteOption::getOptionIndex))
                .orElse(null);
    }

    private OffsetDateTime older(OffsetDateTime current, OffsetDateTime candidate) {
        if (candidate == null) {
            return current;
        }
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private List<GeoPoint> toGeoPoints(List<BusRoutePolylineData.Point> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream().map(point -> new GeoPoint(point.x(), point.y())).toList();
    }

    private List<GeoPoint> toGeoPointsFromCoords(List<CoordPoint> points) {
        if (points == null) {
            return List.of();
        }
        return points.stream().map(point -> new GeoPoint(point.x(), point.y())).toList();
    }

    private boolean samePoint(GeoPoint a, GeoPoint b) {
        return Double.compare(a.x(), b.x()) == 0 && Double.compare(a.y(), b.y()) == 0;
    }

    /** 단계 하나의 좌표와 그 원천 수집 시각. */
    private record Built(LegGeometry geometry, OffsetDateTime sourceFetchedAt) {
    }

    /** 얼려 둔 검색 결과와 단계 → 원본 구간 매핑. */
    private record Snapshot(PathResult path, JourneyCompiler.Compiled compiled) {

        static Snapshot empty() {
            return new Snapshot(null, null);
        }

        SubPathResult subPathOf(int legIndex) {
            if (path == null || compiled == null || path.subPaths() == null) {
                return null;
            }
            Integer index = compiled.subPathIndexOf(legIndex);
            if (index == null || index < 0 || index >= path.subPaths().size()) {
                return null;
            }
            return path.subPaths().get(index);
        }
    }
}
