package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.route.routing.ActualSource;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.GeometrySource;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.MissingAttribute;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.subway.service.SubwayDirectionNormalizer;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.RoutingWalkStep;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * OTP 2.9.0 planConnection 응답(JSON) → 중립 {@link RoutingOutcome} 순수 변환.
 *
 * <p>HTTP 를 모른다(어댑터가 호출·오류처리). stable schema 필드만 읽는다(deprecated 회피).
 * 원천 부재값은 지어내지 않고 {@link MissingAttribute}/{@code UNKNOWN} 으로 남긴다.
 * 대중교통 형상은 그래프에 넣은 pfaedle {@code shapes.txt} 를 그대로 믿고 {@link GeometryQuality#SHAPE_OK} 로
 * 표기한다. trip 별 품질 게이트(coverage-index)는 걸러 내는 trip 이 없어(degraded 0) 걷어 냈다.
 */
@Component
public class OtpPlanConnectionMapper {

    /** feed 시간대(KTDB). serviceDate 해석용. */
    private static final ZoneId FEED_ZONE = ZoneId.of("Asia/Seoul");

    /**
     * @param departureAt 요청 경계에서 확정한 출발시각(응답에 그대로 보존)
     * @param versions    provider/graph/network/schema 버전 묶음
     * @return 경로가 있으면 {@link RoutingResult}, 없고 routingErrors 가 있으면 {@link RoutingFailure}
     */
    public RoutingOutcome map(JsonNode root, Instant departureAt, OtpVersions versions) {
        JsonNode pc = root.path("data").path("planConnection");
        JsonNode edges = pc.path("edges");

        if (!edges.isArray() || edges.isEmpty()) {
            RoutingFailure failure = classifyRoutingErrors(pc.path("routingErrors"));
            if (failure != null) {
                return failure;
            }
            // 경로도 오류도 없음 = 범위 안 무경로.
            return new RoutingResult(RoutingProvider.OTP, versions.schemaVersion(),
                    versions.networkDataVersion(), versions.graphVersion(), null, null,
                    departureAt, List.of(), List.of());
        }

        List<RoutingItinerary> itineraries = new ArrayList<>();
        for (JsonNode edge : edges) {
            itineraries.add(toItinerary(edge.path("node"), versions.graphVersion()));
        }
        return new RoutingResult(RoutingProvider.OTP, versions.schemaVersion(),
                versions.networkDataVersion(), versions.graphVersion(), null, null,
                departureAt, List.of(), itineraries);
    }

    private RoutingFailure classifyRoutingErrors(JsonNode routingErrors) {
        if (!routingErrors.isArray() || routingErrors.isEmpty()) {
            return null;
        }
        JsonNode first = routingErrors.get(0);
        String code = first.path("code").asText("");
        String desc = first.path("description").asText(null);
        RoutingFailure.Kind kind = switch (code) {
            case "OUTSIDE_BOUNDS" -> RoutingFailure.Kind.OUT_OF_SCOPE;
            case "OUTSIDE_SERVICE_PERIOD" -> RoutingFailure.Kind.INPUT_ERROR;
            case "LOCATION_NOT_FOUND", "NO_STOPS_IN_RANGE", "NO_TRANSIT_CONNECTION",
                 "NO_TRANSIT_CONNECTION_IN_SEARCH_WINDOW", "WALKING_BETTER_THAN_TRANSIT" ->
                    RoutingFailure.Kind.NO_ROUTE;
            default -> RoutingFailure.Kind.ENGINE_ERROR;
        };
        return RoutingFailure.of(kind, code, desc);
    }

    private RoutingItinerary toItinerary(JsonNode node, String graphVersion) {
        List<RoutingLeg> legs = new ArrayList<>();
        int moveSeconds = 0;
        for (JsonNode legNode : node.path("legs")) {
            RoutingLeg leg = toLeg(legNode, graphVersion);
            legs.add(leg);
            if (leg.isTransit()) {
                moveSeconds += leg.rideDurationSeconds();
            }
        }
        int walkSeconds = node.path("walkTime").asInt(0);
        int waitSeconds = node.path("waitingTime").asInt(0);
        int transferCount = node.path("numberOfTransfers").asInt(0);
        Instant arrival = legs.isEmpty() ? null : legs.get(legs.size() - 1).alightAt();
        if (arrival == null && !legs.isEmpty()) {
            arrival = legs.get(legs.size() - 1).boardAt();
        }

        // KTDB feed 에 fare rule 이 없어 fareProducts 는 빈 배열 → UNKNOWN(0 생성 금지).
        Fare fare = Fare.unknown();

        // 대중교통 leg 형상이 하나라도 정상 품질이 아니면(=STREET_FALLBACK/MISSING/REVIEW) 네비 미승인
        // (P7 이 최종 판정). SHAPE_OK(pfaedle 게이트 통과)와 OSM_RELATION_OK 는 정상으로 본다.
        boolean anyTransitFallback = legs.stream()
                .anyMatch(l -> l.isTransit() && !isAcceptableGeometry(l.geometry().quality()));
        List<String> blocked = anyTransitFallback
                ? List.of("GEOMETRY_STREET_FALLBACK") : List.of();

        // 후보 정렬 키(ItineraryRanker). 없으면 null — 0 으로 지어내면 맨 앞으로 올라간다.
        JsonNode costNode = node.path("generalizedCost");
        Integer generalizedCost = costNode.isNumber() ? costNode.asInt() : null;

        return new RoutingItinerary(arrival, moveSeconds, waitSeconds, walkSeconds,
                transferCount, false, blocked, fare, legs, generalizedCost);
    }

    private RoutingLeg toLeg(JsonNode legNode, String graphVersion) {
        String otpMode = legNode.path("mode").asText("");
        RoutingMode mode = toMode(otpMode);
        boolean transit = mode == RoutingMode.BUS || mode == RoutingMode.SUBWAY || mode == RoutingMode.RAIL;

        JsonNode route = legNode.path("route");
        String feedId = null;
        String agencyId = text(route.path("agency").path("gtfsId"));
        String sourceRouteId = text(route.path("gtfsId"));
        String sourceRouteName = text(route.path("shortName"));
        if (sourceRouteId != null && sourceRouteId.contains(":")) {
            feedId = sourceRouteId.substring(0, sourceRouteId.indexOf(':'));
        }
        String routeColor = text(route.path("color"));
        String sourceRouteColor = routeColor == null || routeColor.isBlank() ? null : routeColor;
        String tripId = text(legNode.path("trip").path("gtfsId"));

        Instant boardAt = instant(legNode.path("start").path("scheduledTime"));
        Instant alightAt = instant(legNode.path("end").path("scheduledTime"));
        int rideDurationSeconds = legNode.path("duration").asInt(0);
        LocalDate serviceDate = boardAt == null ? null : boardAt.atZone(FEED_ZONE).toLocalDate();

        List<StopOccurrence> stops = transit ? toStops(legNode) : List.of();
        // 6호선은 노선 이름에 라벨이 없어 승·하차역 순서로 정한다(#395).
        String sourceRouteDirection = SubwayDirectionNormalizer.labelFor(sourceRouteId,
                text(route.path("longName")),
                stops.isEmpty() ? null : stops.get(0).sourceStopId(),
                stops.isEmpty() ? null : stops.get(stops.size() - 1).sourceStopId());
        List<RoutingWalkStep> walkSteps = mode == RoutingMode.WALK ? toWalkSteps(legNode) : List.of();

        LegGeometry geometry = toGeometry(legNode, transit, graphVersion);
        Enrichment enrichment = Enrichment.staticOnly(
                transit ? RealtimeCoverage.STATIC_SCHEDULE : RealtimeCoverage.NONE);

        Set<MissingAttribute> missing = EnumSet.noneOf(MissingAttribute.class);
        if (transit) {
            // KTDB feed 한계 + P2-D 매핑 전 상태를 개별 사유로(뭉치지 않음).
            missing.add(MissingAttribute.REALTIME_ID);   // GTFS stop id ↔ 서울 API id 매핑 전
            if (sourceRouteDirection == null) {
                // ~~feed 에 방향/headsign 없음~~ 취소: longName 이 방향을 들고 있다
                // (서울2호선(본선)<내선>). 읽어 낸 leg 은 더 이상 "방향 없음"이 아니다.
                missing.add(MissingAttribute.DIRECTION);
            }
            if (sourceRouteColor == null) {
                missing.add(MissingAttribute.COLOR);      // 그래프 빌드 때 route_color 를 못 채운 노선
            }
        }

        return new RoutingLeg(mode, transit ? otpMode : null, feedId, agencyId,
                sourceRouteId, sourceRouteName, sourceRouteDirection, tripId, serviceDate,
                boardAt, alightAt, rideDurationSeconds, stops, walkSteps,
                geometry, enrichment, missing, sourceRouteColor);
    }

    private List<StopOccurrence> toStops(JsonNode legNode) {
        List<StopOccurrence> fromCalls = new ArrayList<>();
        int idx = 0;
        for (JsonNode call : legNode.path("stopCalls")) {
            JsonNode loc = call.path("stopLocation");
            if (!"Stop".equals(loc.path("__typename").asText(""))) {
                continue;
            }
            // 시간표만 읽는다. 이 배포의 OTP 에는 GTFS-RT 가 없어 realTime 은 언제나 빈 값이고,
            // 실시간은 서버의 버스 도착/위치 캐시가 소유한다(StopOccurrence javadoc).
            JsonNode schedule = call.path("schedule").path("time");
            fromCalls.add(new StopOccurrence(text(loc.path("gtfsId")), null, null, idx++,
                    loc.path("lon").asDouble(), loc.path("lat").asDouble(),
                    text(loc.path("name")),
                    instant(schedule.path("arrival")),
                    instant(schedule.path("departure"))));
        }
        // 실측 확인(fixture 2건): stopCalls 는 board·alight 를 포함한 전량이다 → 비어있지 않으면
        // 그대로 신뢰한다(추가 시각 없는 from/to 합성을 덧붙이면 경계가 중복되고 시각도 비게 된다).
        if (!fromCalls.isEmpty()) {
            return fromCalls;
        }
        // 방어: stopCalls 없는 응답(구버전/이상 케이스)만 시각 없는 from/to 합성으로 폴백.
        List<StopOccurrence> stops = new ArrayList<>();
        int placeIdx = addPlace(stops, legNode.path("from"), 0);
        addPlace(stops, legNode.path("to"), placeIdx);
        return stops;
    }

    private int addPlace(List<StopOccurrence> stops, JsonNode place, int idx) {
        JsonNode stop = place.path("stop");
        String gtfsId = text(stop.path("gtfsId"));
        if (gtfsId == null) {
            return idx;
        }
        stops.add(new StopOccurrence(gtfsId, null, null, idx,
                place.path("lon").asDouble(), place.path("lat").asDouble(),
                text(place.path("name"))));
        return idx + 1;
    }

    private List<RoutingWalkStep> toWalkSteps(JsonNode legNode) {
        List<RoutingWalkStep> steps = new ArrayList<>();
        for (JsonNode s : legNode.path("steps")) {
            steps.add(new RoutingWalkStep(s.path("distance").asDouble(0),
                    text(s.path("relativeDirection")), text(s.path("streetName"))));
        }
        return steps;
    }

    private LegGeometry toGeometry(JsonNode legNode, boolean transit, String graphVersion) {
        JsonNode geom = legNode.path("legGeometry");
        String points = text(geom.path("points"));
        if (points == null) {
            return LegGeometry.missing("no legGeometry");
        }
        // 대중교통은 그래프의 pfaedle shapes.txt 형상이다. shape 가 없는 소수 trip(약 0.5%)은 OTP 가
        // 정류장 직선을 주는데, 앱도 polyline 이 없으면 정류장 직선으로 그리므로 결과가 같다.
        if (transit) {
            return new LegGeometry(GeometrySource.GTFS_SHAPE, GeometryQuality.SHAPE_OK,
                    "pfaedle gtfs shape", graphVersion, points, points);
        }
        // 도보: OTP OSM 도보 경로가 최종 형상이지만 street 출처로만 표기(품질 세분화는 별건).
        return new LegGeometry(GeometrySource.OTP_STREET, GeometryQuality.STREET_FALLBACK,
                "OTP street walk", null, points, points);
    }

    /** 형상 품질을 정상으로 볼지(네비 승인 후보). */
    private static boolean isAcceptableGeometry(GeometryQuality quality) {
        return quality == GeometryQuality.OSM_RELATION_OK || quality == GeometryQuality.SHAPE_OK;
    }

    private RoutingMode toMode(String otpMode) {
        return switch (otpMode) {
            case "WALK" -> RoutingMode.WALK;
            case "BUS" -> RoutingMode.BUS;
            case "SUBWAY", "TRAM" -> RoutingMode.SUBWAY;
            case "RAIL" -> RoutingMode.RAIL;
            default -> RoutingMode.UNSUPPORTED;
        };
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.isMissingNode() ? null : n.asText();
    }

    private static Instant instant(JsonNode n) {
        String s = text(n);
        return s == null ? null : OffsetDateTime.parse(s).toInstant();
    }

    /** provider/graph/network/schema 버전 묶음(어댑터가 설정에서 채워 넘긴다). */
    public record OtpVersions(String schemaVersion, String networkDataVersion, String graphVersion) {
    }
}
