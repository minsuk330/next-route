package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeoPoint;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.GeometryQuality;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.LegGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.ManeuverGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.SegmentGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.StopGeometry;
import watoo.grd.nextroute.application.journey.dto.JourneyNavigationGeometry.TransferLine;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.routing.RailServiceClassifier;
import watoo.grd.nextroute.application.subway.service.SubwayLineStationIndex;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
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
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 여정 전체의 지도·진행 판정용 좌표를 모아 준다.
 *
 * <p>좌표 원천이 단계 종류마다 다르다.
 * <ul>
 *   <li>버스 타는 단계 — 시작 시 얼려 둔 검색 결과의 shapes.txt 좌표를 정류장 위치에서 자른 구간</li>
 *   <li>지하철 타는 단계 — 같은 검색 결과의 구간 좌표</li>
 *   <li>걷기 단계 — 같은 검색 결과의 TMAP 보행자 좌표</li>
 * </ul>
 *
 * <p>세 원천 모두 비어 있을 수 있다. 버스·지하철은 shape 없는 trip 이 있고, 걷기는 검색이 바쁠 때 TMAP 호출을 건너뛴다. 그래서 <b>좌표가 없으면
 * 단계를 빼는 대신 직선으로 채우고 품질을 낮춘다.</b> 빼면 앱이 구간 수를 서버와 다르게 세고
 * 진행 순번이 어긋난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyNavigationGeometryService implements GetJourneyNavigationGeometryUseCase {

    /** 여정을 시작할 때 세션과 1번 개정본이 같은 스냅샷을 받는다. 그 뒤로는 개정본만 바뀐다. */
    private static final int FIRST_REVISION = 1;

    /** 앱 {@code isRouteDisplayColor} 와 같은 형식. 형식이 다르면 앱이 버리므로 서버에서 먼저 거른다. */
    private static final Pattern DISPLAY_COLOR =
            Pattern.compile("^#[0-9A-Fa-f]{6}$");

    private final JourneySessionRepository sessionRepository;
    private final JourneyRevisionRepository revisionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final BusShapeStopSplitter shapeSplitter;
    private final BusRouteStopRepository busRouteStopRepository;
    private final JourneyCompiler compiler;
    private final GeometrySegmentAllocator allocator;
    private final WalkManeuverAnchorer maneuverAnchorer;
    private final ObjectMapper objectMapper;
    private final BusRealtimeStopIds busRealtimeStopIds;
    // 지하철 단계 완행 전체 역(allStops)·버전 키의 카탈로그 판(subway-express S2).
    private final RailServiceClassifier railServiceClassifier;
    // 묶인 열차 기준 역 목록·버전 키의 바인딩 판(subway-express S4).
    private final SubwayBoundStopsResolver boundStops;
    // 지하철 역마다 갈아탈 노선(#510).
    private final SubwayLineStationIndex stationIndex;

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
        Snapshot snapshot = recompile(snapshotOf(journey, revision));
        Map<Long, SubwayLegBinding> bindings = boundStops.bindingsOf(legs);

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
                case WALK, TRANSFER_WALK -> new Built(walkLeg(leg, sub), null);
                case RIDE_SUBWAY -> new Built(
                        withTransfers(subwayRide(leg, sub, bindings.get(leg.getId())), leg.getRouteId()), null);
                default -> new Built(snapshotLeg(leg, sub), null);
            };
            geometries.add(built.geometry().withDisplayColor(
                    displayColor(leg, ownerOfOptions(legs, i), optionsByLeg, sub)));
            oldestFetchedAt = older(oldestFetchedAt, built.sourceFetchedAt());
        }

        return new JourneyNavigationGeometry(journeyId, revision, geometries, oldestFetchedAt,
                JourneyNavigationGeometry.versionKey(revision, railServiceClassifier.catalogVersion(),
                        SubwayBoundStopsResolver.versionToken(bindings.values()),
                        busSelectionToken(legs, optionsByLeg)));
    }

    /**
     * 첫 후보가 아닌 노선을 고른 버스 구간들(#598) — {@code b{단계}.{후보}} 를 이어 붙인다. 없으면 null.
     * 후보는 기다리는 단계가 들고 있다.
     */
    static String busSelectionToken(List<JourneyLeg> legs, Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        StringBuilder token = new StringBuilder();
        for (JourneyLeg leg : legs) {
            Long selected = leg.getSelectedRouteOptionId();
            if (leg.getType() != JourneyLegType.WAIT_BUS || selected == null) {
                continue;
            }
            optionsByLeg.getOrDefault(leg.getId(), List.of()).stream()
                    .filter(option -> selected.equals(option.getId()) && option.getOptionIndex() != 0)
                    .findFirst()
                    .ifPresent(option -> token.append('b').append(leg.getLegIndex())
                            .append('.').append(option.getOptionIndex()));
        }
        return token.isEmpty() ? null : token.toString();
    }

    /**
     * 지하철 단계 역마다 갈아탈 수 있는 다른 노선(#510) — 앱 지도가 역 이름 옆에 노선 색 점을 찍는다. 단계 노선
     * ({@code routeId}, 실시간 line_id)을 빼고 계산한다. 그 노선을 역 마스터가 모르면 붙이지 않는다(제 노선이 환승으로 나온다).
     */
    private LegGeometry withTransfers(LegGeometry geometry, String lineId) {
        return geometry.mapStops(stop -> stop.withTransfers(
                stationIndex.transfersAt(stop.stopName(), stop.y(), stop.x(), lineId)
                        .map(transfers -> transfers.stream()
                                .map(t -> new TransferLine(t.lineId(), t.lineName()))
                                .toList())
                        .orElse(null)));
    }

    /**
     * 지하철 타는 단계. 열차가 아직 안 묶였으면 S2 그대로(계획 정차열 + 완행 {@code allStops}). 묶였으면(subway-express S4) 그
     * 열차의 정차역으로 바꾼다:
     *
     * <ul>
     *   <li>급행 — 계획 첫·끝 역 사이를 완행으로 펼치고 그 열차가 서는 역만 {@code trainStops=true}, {@code stops} 는 서는 역만.
     *       패턴을 못 만들거나 승·하차역에 서지 않으면 S2 그대로</li>
     *   <li>일반 — 계획이 급행 정차열이었으면 완행으로 펼쳐 전부 서는 역({@code stops} = 완행). 계획이 이미 완행이면 그대로</li>
     *   <li>모름·배포 전 미분류 — S2 그대로</li>
     * </ul>
     * 구버전 앱은 {@code stops} 를 그대로 세므로, 이 교체로 남은 역 수가 실제 열차 기준이 된다.
     */
    private LegGeometry subwayRide(JourneyLeg leg, SubPathResult sub, SubwayLegBinding binding) {
        LegGeometry planned = withStops(snapshotLeg(leg, sub), subwayStations(sub))
                .withAllStops(subwayAllStops(leg, sub));
        if (binding == null || binding.getTrainKind() == null
                || binding.getTrainKind() == watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.UNKNOWN) {
            return planned;
        }
        boolean express = binding.getTrainKind()
                == watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.EXPRESS;
        if (!express && planned.allStops() != null
                && planned.allStops().stream().allMatch(stop -> Boolean.TRUE.equals(stop.trainStops()))) {
            return planned; // 일반 열차 · 완행 계획 — 바꿀 것이 없다
        }
        Optional<List<RailServiceClassifier.RailStop>> expanded = expandPlanned(sub);
        if (expanded.isEmpty()) {
            return planned;
        }
        Optional<watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver.ResolvedPattern> pattern =
                express ? boundStops.patternOf(binding) : Optional.empty();
        if (express && pattern.isEmpty()) {
            return planned;
        }
        List<RailServiceClassifier.RailStop> path = expanded.get();
        List<StopGeometry> all = new ArrayList<>(path.size());
        List<StopGeometry> stops = new ArrayList<>(path.size());
        for (int i = 0; i < path.size(); i++) {
            RailServiceClassifier.RailStop stop = path.get(i);
            boolean stopsHere = !express || pattern.get().stopsAt(stop.stationId());
            all.add(new StopGeometry(i, null, stop.name(), stop.lon(), stop.lat(), null, stopsHere));
            if (stopsHere) {
                stops.add(new StopGeometry(stops.size(), null, stop.name(), stop.lon(), stop.lat()));
            }
        }
        if (!Boolean.TRUE.equals(all.get(0).trainStops()) || !Boolean.TRUE.equals(all.get(all.size() - 1).trainStops())) {
            return planned; // 승·하차역에 서지 않는 열차 — 고를 수 없었어야 한다. 계획을 지킨다
        }
        return withStops(planned, stops).withAllStops(all);
    }

    /** 계획 정차열의 첫·끝 역 사이 완행 경로(좌표·역 id 포함). 못 펼치면 empty. */
    private Optional<List<RailServiceClassifier.RailStop>> expandPlanned(SubPathResult sub) {
        List<StopGeometry> planned = subwayStations(sub);
        if (planned.size() < 2 || sub.lanes() == null || sub.lanes().isEmpty()) {
            return Optional.empty();
        }
        List<RailServiceClassifier.PlannedStop> plannedStops = new ArrayList<>(planned.size());
        for (StopGeometry stop : planned) {
            if (stop.stopName() == null || stop.x() == null || stop.y() == null) {
                return Optional.empty();
            }
            plannedStops.add(new RailServiceClassifier.PlannedStop(stop.stopName(), stop.x(), stop.y()));
        }
        return railServiceClassifier.expandAllStops(sub.lanes().get(0).name(), plannedStops);
    }

    // ── 버스 ────────────────────────────────────────────────────────────────

    /**
     * 버스 타는 단계. 검색 결과의 shapes.txt 선을 정류장 구간 단위로 내려준다.
     *
     * <p>노선·정류장 순번을 아직 해석하지 못한 단계가 있다. 그때는 정류장 구간을 만들 수 없으니
     * 검색 결과 좌표를 선분 단위로, 그것도 없으면 직선으로 떨어진다.
     */
    private Built busLeg(JourneyLeg leg, JourneyLeg optionOwner,
                         Map<Long, List<JourneyLegRouteOption>> optionsByLeg,
                         SubPathResult sub,
                         Map<String, List<RouteStopProjection>> routeStopsByRouteId) {
        JourneyLegRouteOption option = selectedOption(leg, optionOwner, optionsByLeg);
        if (!hasBusScope(option)) {
            log.debug("버스 구간 서울 노선표 조회 불가 — legIndex={}, 노선·순번 미해석. 검색 결과 정류장으로", leg.getLegIndex());
            return new Built(snapshotBusLeg(leg, sub), null);
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
                        stop.getLatitude(),
                        stop.getArsId()))
                .toList();

        // 선은 검색 결과의 shapes.txt 형상이다 — "지도에서 보기"가 그리는 선과 같다. 진행 판정용
        // 정류장 구간은 그 선을 정류장 위치에서 잘라 만든다. 선이 없으면 정류장 직선(미리보기와 같음).
        List<SegmentGeometry> raw = shapeSplitter.split(toGeoPointsFromCoords(
                sub == null ? null : sub.polyline()), stops);
        if (raw.isEmpty()) {
            raw = shapeSplitter.straight(stops);
        }
        if (raw.isEmpty()) {
            return new Built(withStops(snapshotLeg(leg, sub), stops), null);
        }
        List<SegmentGeometry> segments = allocator.distribute(raw, leg.getPlannedDurationSec());
        return new Built(assemble(leg, option.getRouteId(), segments, stops), null);
    }

    /**
     * 서울 노선표로 정류장을 못 꺼낸 버스 단계(경기·지방 노선, 순번이 모호한 서울 노선 등).
     * 얼려 둔 검색 결과(OTP·GTFS)가 그 버스가 서는 정류장을 이미 들고 있으니 그것으로 정류장과 구간을 만든다.
     *
     * <p>정류장이 2곳 이상이면 서울 경로와 같은 방법(선을 정류장에서 자르기)으로 구간을 만든다 — 앱의 구간 계약이
     * 같아야 한다. 정류장을 하나도 못 구하면 선은 내려보내되 품질을 {@code APPROXIMATE} 로 낮춰 앱이 정류장 기반
     * 진행 판정을 기대하지 않게 한다.
     *
     * <p>여기서의 {@code seq} 는 서울 노선 순번이 아니라 승차 정류장부터 센 순서(0부터)다. 정류장을 세는 데는 같고,
     * 서울 실시간 버스 위치와 대조하는 값으로는 쓰지 않는다(그 대조는 서울 노선표 경로에서만 일어난다).
     */
    private LegGeometry snapshotBusLeg(JourneyLeg leg, SubPathResult sub) {
        List<StopGeometry> stops = snapshotBusStops(sub);
        if (stops.isEmpty()) {
            LegGeometry base = snapshotLeg(leg, sub);
            return assemble(leg, leg.getRouteId(),
                    withQuality(base.segments(), worse(base.quality(), GeometryQuality.APPROXIMATE), "NO_STOPS"),
                    List.of());
        }
        List<SegmentGeometry> raw = shapeSplitter.split(toGeoPointsFromCoords(
                sub == null ? null : sub.polyline()), stops);
        if (raw.isEmpty()) {
            raw = shapeSplitter.straight(stops);
        }
        if (raw.isEmpty()) {
            return withStops(snapshotLeg(leg, sub), stops);
        }
        return assemble(leg, leg.getRouteId(), allocator.distribute(raw, leg.getPlannedDurationSec()), stops);
    }

    /**
     * 검색 결과의 버스 정류장 목록. 없으면 승차·하차 두 곳이라도 만든다.
     * {@code stopId} 는 실시간 도착 API 가 받는 id(길찾기 응답 {@code startLocalStationID} 와 같은 규칙)다.
     */
    private List<StopGeometry> snapshotBusStops(SubPathResult sub) {
        if (sub == null) {
            return List.of();
        }
        if (sub.stations() != null && sub.stations().size() >= 2) {
            return sub.stations().stream()
                    .sorted(Comparator.comparingInt(StationResult::index))
                    .map(station -> new StopGeometry(
                            station.index(),
                            busRealtimeStopIds.of(station.stationID()).orElse(null),
                            station.stationName(),
                            station.x(),
                            station.y()))
                    .toList();
        }
        if (sub.startX() == null || sub.startY() == null || sub.endX() == null || sub.endY() == null) {
            return List.of();
        }
        return List.of(
                new StopGeometry(0, sub.startLocalStationID(), sub.startName(), sub.startX(), sub.startY()),
                new StopGeometry(1, sub.endLocalStationID(), sub.endName(), sub.endX(), sub.endY()));
    }

    private boolean hasBusScope(JourneyLegRouteOption option) {
        return option != null
                && option.getRouteId() != null && !option.getRouteId().isBlank()
                && option.getBoardingSeq() != null && option.getAlightingSeq() != null
                && option.getBoardingSeq() < option.getAlightingSeq();
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
     * 얼려 둔 검색 결과가 준 계획 역 목록. 승차역부터 하차역까지 순서대로다.
     *
     * <p><b>진행의 근거가 아니다.</b> 열차가 어디쯤 왔는지는 실시간 정보가 답할 일이고, 여기 있는
     * 역 목록은 순서·이름·남은 역 수·하차역 여부에만 쓴다. 검색 시점에 얼린 값이라 그 뒤로 바뀌지
     * 않는다는 점이 오히려 필요하다 — 안내가 조회할 때마다 달라지면 안 된다.
     *
     * <p><b>{@code stopId} 를 비워 둔다.</b> 검색 결과의 역 식별자와 실시간 정보의 역 식별자는
     * 서로 다른 체계다. 값을 실어 두면 언젠가 둘을 맞대어 보는 코드가 생기고, 그때는 아무것도
     * 일치하지 않는다. 실시간 쪽과의 대조는 역 이름으로 한다.
     */
    private List<StopGeometry> subwayStations(SubPathResult sub) {
        if (sub == null || sub.stations() == null || sub.stations().isEmpty()) {
            return List.of();
        }
        return sub.stations().stream()
                .sorted(Comparator.comparingInt(StationResult::index))
                .map(station -> new StopGeometry(
                        station.index(),
                        null,
                        station.stationName(),
                        station.x(),
                        station.y()))
                .toList();
    }

    /**
     * 지하철 타는 단계의 완행 전체 역({@code allStops}, subway-express S2). 새 앱이 'N개 역'·남은 역을 이 열차가 서는 역만으로
     * 센다(D9). {@code stops}(계획 정차열)는 손대지 않는다 — 구버전 앱이 그것을 센다.
     *
     * <ul>
     *   <li>급행(일반·모름이 아닌 종류)이면 계획 정차열의 첫·끝 역 사이를 완행 순서로 펼치고(S1 해석기) 계획 정차열에 있는
     *       역만 {@code trainStops=true}</li>
     *   <li>일반·모름이거나 펼치지 못하면 계획 정차열 그대로 전부 {@code true} — 세는 값은 같다(계획 정차열이 곧 이 열차가 서는
     *       역이다). 지나치는 역만 못 보여 준다</li>
     * </ul>
     * 미바인딩 여정 기준이다. 열차가 묶이면(S4) 그 열차의 해석된 패턴으로 바꾼다.
     */
    private List<StopGeometry> subwayAllStops(JourneyLeg leg, SubPathResult sub) {
        List<StopGeometry> planned = subwayStations(sub);
        List<StopGeometry> asTrainStops = planned.stream()
                .map(s -> new StopGeometry(s.seq(), s.stopId(), s.stopName(), s.x(), s.y(), null, true))
                .toList();
        String kind = sub != null && sub.trainKind() != null ? sub.trainKind() : leg.getGuidance().trainKind();
        if (kind == null || "NORMAL".equals(kind) || planned.size() < 2 || sub.lanes() == null
                || sub.lanes().isEmpty()) {
            return asTrainStops;
        }
        List<RailServiceClassifier.PlannedStop> plannedStops = new ArrayList<>(planned.size());
        for (StopGeometry stop : planned) {
            if (stop.stopName() == null || stop.x() == null || stop.y() == null) {
                return asTrainStops;
            }
            plannedStops.add(new RailServiceClassifier.PlannedStop(stop.stopName(), stop.x(), stop.y()));
        }
        Optional<List<RailServiceClassifier.RailStop>> expanded = railServiceClassifier.expandAllStops(
                sub.lanes().get(0).name(), plannedStops);
        if (expanded.isEmpty()) {
            return asTrainStops;
        }
        List<RailServiceClassifier.RailStop> stops = expanded.get();
        List<StopGeometry> out = new ArrayList<>(stops.size());
        for (int i = 0; i < stops.size(); i++) {
            RailServiceClassifier.RailStop stop = stops.get(i);
            // 지하철 stopId 는 비운다(subwayStations 와 같은 이유 — 검색·실시간 역 id 체계가 다르다)
            out.add(new StopGeometry(i, null, stop.name(), stop.lon(), stop.lat(), null, stop.trainStops()));
        }
        return out;
    }

    /**
     * 걷는 단계. 기존 좌표 조립에 turn-by-turn 안내점만 얹는다.
     *
     * <p>안내점은 <b>얼려 둔 검색 결과의 {@code walkSteps}</b> 에서 나온다. 조회 때 TMAP 을 다시
     * 부르지 않는다 — 이 응답은 개정본마다 한 번 받는 캐시 대상이고, 경로가 바뀌지 않았는데 안내가
     * 달라지면 앱 캐시와 서버가 서로 다른 말을 한다.
     *
     * <p>안내점이 없거나 못 붙는 것은 정상 경로다. 좌표·구간은 그대로 내려가고 안내점만 빈다.
     */
    private LegGeometry walkLeg(JourneyLeg leg, SubPathResult sub) {
        LegGeometry base = snapshotLeg(leg, sub);
        if (sub == null) {
            return base;
        }
        // anchor 기준은 base.points() 가 아니라 sub.polyline() 이다. points 는 붙이는 과정에서
        // 연속 중복 좌표를 지우므로 구간 순번의 기준이 될 수 없다.
        return withManeuvers(base,
                maneuverAnchorer.anchor(sub.walkSteps(), sub.polyline(), base.quality()));
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

    // ── 색 ──────────────────────────────────────────────────────────────────

    /**
     * 타는 단계의 지도 선 색. 검색 응답 lane 의 {@code routeDisplayColor} 를 그대로 쓴다 — 미리보기가
     * 같은 값으로 그리므로 두 지도가 같은 색이 된다.
     *
     * <p>선택된 노선 후보의 lane 색을 먼저, 없으면 색이 있는 첫 lane 을 쓴다. 도보·대기는 null.
     */
    private String displayColor(JourneyLeg leg, JourneyLeg optionOwner,
                                Map<Long, List<JourneyLegRouteOption>> optionsByLeg,
                                SubPathResult sub) {
        if (sub == null || sub.lanes() == null || sub.lanes().isEmpty()
                || (leg.getType() != JourneyLegType.RIDE_BUS
                && leg.getType() != JourneyLegType.RIDE_SUBWAY)) {
            return null;
        }
        JourneyLegRouteOption option = selectedOption(leg, optionOwner, optionsByLeg);
        Integer laneIndex = option == null ? null : option.getLaneIndex();
        if (laneIndex != null && laneIndex >= 0 && laneIndex < sub.lanes().size()) {
            String selected = validColor(sub.lanes().get(laneIndex).routeDisplayColor());
            if (selected != null) {
                return selected;
            }
        }
        return sub.lanes().stream()
                .map(lane -> validColor(lane.routeDisplayColor()))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private String validColor(String color) {
        return color != null && DISPLAY_COLOR.matcher(color).matches() ? color : null;
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

    /** 정류장만 갈아끼운다. 나머지 필드는 반드시 보존한다 — 하나라도 흘리면 앱 계약이 깨진다. */
    private LegGeometry withStops(LegGeometry geometry, List<StopGeometry> stops) {
        return new LegGeometry(
                geometry.legIndex(), geometry.type(), geometry.routeId(),
                geometry.plannedDurationSeconds(), geometry.totalDistanceMeters(),
                geometry.quality(), geometry.points(), geometry.segments(), stops,
                geometry.maneuvers(), geometry.displayColor(), geometry.allStops());
    }

    /** 안내점만 갈아끼운다. {@link #withStops} 와 같은 이유로 나머지를 보존한다. */
    private LegGeometry withManeuvers(LegGeometry geometry, List<ManeuverGeometry> maneuvers) {
        return new LegGeometry(
                geometry.legIndex(), geometry.type(), geometry.routeId(),
                geometry.plannedDurationSeconds(), geometry.totalDistanceMeters(),
                geometry.quality(), geometry.points(), geometry.segments(), geometry.stops(),
                maneuvers, geometry.displayColor(), geometry.allStops());
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
     * 지금 그리는 <b>개정본의</b> 경로 원본. 세션이 든 것이 아니다.
     *
     * <p>{@code JourneySession.routeSnapshot} 은 여정을 시작할 때의 원본이고 <b>영원히 바뀌지
     * 않는다.</b> 재탐색을 수락하면 개정본만 오르므로, 세션 쪽을 쓰면 지도와 진행 엔진에 옛 경로가
     * 계속 내려간다 — 오류 없이, 조용히.
     *
     * <p><b>폴백은 1번 개정본에만.</b> 그때만 두 값이 같은 것이 보장된다(여정 시작이 같은 스냅샷을
     * 두 곳에 넣는다). 2번 이상인데 개정본 행이 없으면 세션 스냅샷은 <b>반드시 다른 경로</b>이므로,
     * 조용히 옛 경로를 그리느니 실패한다.
     */
    private JsonNode snapshotOf(JourneySession journey, int revision) {
        Optional<JourneyRevision> found = revisionRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(journey.getId(), revision);
        if (found.isPresent()) {
            return found.get().getRouteSnapshot();
        }
        if (revision != FIRST_REVISION) {
            log.error("[JourneyGeometry] 개정본 스냅샷이 없다 — journeyId={} revision={}",
                    journey.getId(), revision);
            throw new IllegalStateException(
                    "개정본 스냅샷이 없다: journeyId=" + journey.getId() + " revision=" + revision);
        }
        log.warn("[JourneyGeometry] 1번 개정본 행이 없어 세션 스냅샷으로 그린다 — journeyId={}",
                journey.getId());
        return journey.getRouteSnapshot();
    }

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
