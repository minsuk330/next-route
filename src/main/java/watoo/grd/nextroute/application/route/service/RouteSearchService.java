package watoo.grd.nextroute.application.route.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.SearchMeta;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.WalkSegment;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.exception.TmapApiException;
import watoo.grd.nextroute.application.route.observability.DegradeReason;
import watoo.grd.nextroute.application.route.observability.RouteSearchContext;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.observability.SearchComponent;
import watoo.grd.nextroute.application.route.port.in.SearchRouteUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort;
import watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.WalkSearchCommand;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class RouteSearchService implements SearchRouteUseCase {

    private static final int TRAFFIC_TYPE_WALK = 3;
    /** ODsay 에러코드: 출/도착지가 700m 이내. 이 경우 TMAP 도보 경로로만 응답한다. */
    private static final int ODSAY_CODE_WITHIN_WALK_DISTANCE = -98;
    /** 도보 전용 응답의 pathType (ODsay pathType=3 재사용, subPath는 전부 trafficType=3). */
    private static final int PATH_TYPE_WALK_ONLY = 3;

    private final OdSayApiPort odSayApiPort;
    private final TmapPedestrianPort tmapPort;
    private final RoutePolylineEnricher polylineEnricher;
    private final WalkSegmentEnricher walkSegmentEnricher;
    private final TransferArrivalEnricher transferArrivalEnricher;
    private final RouteSearchMetrics metrics;
    private final RouteSearchDeadlineProperties deadlineProperties;
    // provider 전환(national-otp P2). 기본 ODSAY 라 아래 branch 는 default 에서 실행되지 않는다.
    private final RoutingProviderSelector providerSelector;
    private final LegacyRouteResponseMapper legacyRouteResponseMapper;
    private final DepartureTimeResolver departureTimeResolver;
    private final Clock clock;
    private final watoo.grd.nextroute.application.route.timeline.RouteTimelineService routeTimelineService;

    @Override
    public RouteSearchResult search(RouteSearchRequest request) {
        return searchInternal(request, true);
    }

    @Override
    public RouteSearchResult searchWithoutRealtime(RouteSearchRequest request) {
        return searchInternal(request, false);
    }

    private RouteSearchResult searchInternal(RouteSearchRequest request, boolean withRealtime) {
        Instant searchStartedAt = Instant.now();
        RouteSearchContext ctx = RouteSearchContext.start(deadlineProperties.getDeadlineMs());
        try {
            // 완전성 판정은 모든 단계가 ctx 에 보고를 마친 뒤 확정되므로, 반환 직전에 meta 로 부착한다.
            return runSearch(request, withRealtime, searchStartedAt, ctx).withMeta(buildMeta(ctx));
        } finally {
            metrics.recordSearch(ctx.outcome(), ctx.elapsedNanos());
            metrics.recordDegradations(ctx.degradations());
        }
    }

    /**
     * 내부 완전성 판정을 응답 계약(Step 5)으로 변환한다. degraded 목록은 클라이언트 응답 필드에
     * 실제로 영향을 주는 OPTIONAL 단계만 담는다(AUXILIARY 검색 이력은 제외). MANDATORY 실패는
     * body 가 아니라 504/502 에러로 나가므로 여기 도달하지 않는다.
     */
    private SearchMeta buildMeta(RouteSearchContext ctx) {
        List<SearchMeta.DegradedComponent> degraded = ctx.degradations().entrySet().stream()
                .filter(e -> e.getKey().isOptional())
                .map(e -> new SearchMeta.DegradedComponent(e.getKey().tag(), e.getValue().tag()))
                .toList();
        return new SearchMeta(ctx.outcome().tag(), degraded);
    }

    private RouteSearchResult runSearch(RouteSearchRequest request, boolean withRealtime,
                                        Instant searchStartedAt, RouteSearchContext ctx) {
        // provider 전환(national-otp P2). 공개 요청은 내부 OTP 요청이 아니므로 select(false).
        // 기본 provider 는 ODSAY 라 default 구성에서는 아래 OTP branch 를 타지 않는다(회귀 0).
        if (providerSelector.select(false) == RoutingProvider.OTP) {
            return runOtpSearch(request, ctx);
        }

        RouteSearchResult result;
        long odsayStart = System.nanoTime();
        try {
            // 필수 단계도 예산 안에서만 기다린다: min(단계 상한, 남은 시간).
            result = odSayApiPort.searchPath(
                    request.startX(), request.startY(),
                    request.endX(), request.endY(),
                    ctx.budgetMillis(deadlineProperties.getOdsayMs())
            );
        } catch (OdSayApiException e) {
            if (e.getErrorCode() == ODSAY_CODE_WITHIN_WALK_DISTANCE) {
                // 출/도착지 700m 이내 → ODsay 대중교통 경로 없음. TMAP 도보 경로로만 응답.
                return walkOnlyFallback(request, e, ctx);
            }
            if (e.getKind() == OdSayApiException.Kind.TIMEOUT) {
                ctx.degrade(SearchComponent.ODSAY, DegradeReason.TIMEOUT);
                metrics.recordDeadlineExceeded(SearchComponent.ODSAY);
            } else {
                ctx.degrade(SearchComponent.ODSAY, DegradeReason.UPSTREAM_ERROR);
            }
            throw e;
        } finally {
            metrics.recordStage(SearchComponent.ODSAY, ctx.isDegraded(SearchComponent.ODSAY),
                    System.nanoTime() - odsayStart);
        }

        // 1. 지하철 노선 폴리라인 보강 (trafficType=1)
        RouteSearchResult afterPolyline = result;
        result = stage(ctx, SearchComponent.POLYLINE,
                () -> polylineEnricher.enrich(afterPolyline, ctx));

        // 2. TMAP 보행자 경로 보강 (trafficType=3)
        RouteSearchResult afterWalk = result;
        result = stage(ctx, SearchComponent.WALK,
                () -> walkSegmentEnricher.enrich(afterWalk,
                        request.startX(), request.startY(), request.startName(),
                        request.endX(), request.endY(), request.endName(), ctx,
                        deadlineProperties));

        // 3. 환승 도착예측 보강 (trafficType=2 버스) — baseline(STATIC)은 생략해 fan-out 낭비 방지.
        // withRealtime=false 는 요청이 실시간을 요구하지 않은 것이므로 degradation 이 아니다(단계 미적용).
        if (withRealtime) {
            RouteSearchResult afterBus = result;
            result = stage(ctx, SearchComponent.BUS_REALTIME,
                    () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx));
        }

        return result;
    }

    /**
     * OTP provider 경로. RoutingPort 로 검색해 중립 결과를 legacy 응답으로 매핑한다.
     * ODsay 전용 enricher(지하철 폴리라인·TMAP 도보 상세·서울 실시간)는 타지 않는다 —
     * 대중교통 형상은 P3(OSM relation), 실시간 보강은 P2-D(ID 매핑)에서 붙는다.
     */
    private RouteSearchResult runOtpSearch(RouteSearchRequest request, RouteSearchContext ctx) {
        long budget = ctx.budgetMillis(deadlineProperties.getOdsayMs());
        RoutingDeadline deadline = RoutingDeadline.of(clock, budget);
        Instant departureAt = departureTimeResolver.resolve(request.departureAt());
        RoutingQuery query = new RoutingQuery(
                request.startX(), request.startY(), request.startName(),
                request.endX(), request.endY(), request.endName(),
                departureAt, java.util.Set.of(), null, false, "otp-v2",
                RoutingProvider.OTP, null);

        long start = System.nanoTime();
        RoutingOutcome outcome;
        try {
            outcome = providerSelector.portFor(RoutingProvider.OTP).search(query, deadline);
        } finally {
            metrics.recordStage(SearchComponent.ODSAY, ctx.isDegraded(SearchComponent.ODSAY),
                    System.nanoTime() - start);
        }

        if (outcome instanceof RoutingFailure failure) {
            return handleOtpFailure(failure, ctx);
        }
        // 각 itinerary 를 하나의 시간축으로 합성해 보강 total 로 정렬(FEASIBLE 우선). 실시간은 coverage 게이트+주입 lookup.
        // 남은 예산으로 실시간 상한을 걸어(M1) 순차 외부콜이 검색 deadline 을 넘기지 않게 한다. 계측은 BUS_REALTIME stage.
        long rtStart = System.nanoTime();
        RoutingResult result = routeTimelineService.reorderByEffectiveTotal(
                (RoutingResult) outcome, departureAt, deadline.remainingMillis(clock));
        metrics.recordStage(SearchComponent.BUS_REALTIME,
                ctx.isDegraded(SearchComponent.BUS_REALTIME), System.nanoTime() - rtStart);
        return legacyRouteResponseMapper.toLegacy(result);
    }

    /**
     * OTP 실패를 응답/에러로 변환한다. 범위 밖·무경로는 빈 결과(정상 무경로), timeout/엔진장애/입력오류는
     * 기존 route-search 실패 신호({@link OdSayApiException} kind)로 올려 컨트롤러가 504/502/400 으로 낸다.
     */
    private RouteSearchResult handleOtpFailure(RoutingFailure failure, RouteSearchContext ctx) {
        return switch (failure.kind()) {
            case OUT_OF_SCOPE, NO_ROUTE -> new RouteSearchResult(0, 0, 0, 0, 0, List.of());
            case TIMEOUT -> {
                ctx.degrade(SearchComponent.ODSAY, DegradeReason.TIMEOUT);
                metrics.recordDeadlineExceeded(SearchComponent.ODSAY);
                throw OdSayApiException.timeout("OTP: " + failure.message());
            }
            case ENGINE_ERROR -> {
                ctx.degrade(SearchComponent.ODSAY, DegradeReason.UPSTREAM_ERROR);
                throw OdSayApiException.upstream("OTP: " + failure.message());
            }
            case INPUT_ERROR -> throw new OdSayApiException(400, "OTP: " + failure.message());
        };
    }

    /** 단계 실행 + 소요시간 기록. degradation 은 각 단계가 ctx 에 보고한 결과를 그대로 읽는다. */
    private <T> T stage(RouteSearchContext ctx, SearchComponent component, Supplier<T> body) {
        long startNanos = System.nanoTime();
        try {
            return body.get();
        } finally {
            metrics.recordStage(component, ctx.isDegraded(component), System.nanoTime() - startNanos);
        }
    }

    /**
     * ODsay -98(출/도착지 700m 이내) 대응: TMAP 보행자 경로만으로 응답을 구성한다.
     * TMAP이 빈 결과(서비스지역 외)이거나 호출 실패면 원래 -98 에러를 그대로 전파한다.
     */
    private RouteSearchResult walkOnlyFallback(RouteSearchRequest request, OdSayApiException cause,
                                               RouteSearchContext ctx) {
        // 도보 전용 응답에서 TMAP 은 선택 보강이 아니라 응답 생성 수단이다.
        // 실패하면 응답 자체가 없으므로 ODSAY(필수) degradation 으로도 기록해 outcome=FAILED 가 된다.
        WalkSegment segment;
        try {
            segment = tmapPort.search(new WalkSearchCommand(
                    request.startX(), request.startY(),
                    request.endX(), request.endY(),
                    request.startName(), request.endName()));
        } catch (TmapApiException te) {
            log.warn("[Route] -98 fallback TMAP 실패 — 원래 ODsay 에러 전파: {}", te.getMessage());
            ctx.degrade(SearchComponent.WALK, DegradeReason.UPSTREAM_ERROR);
            ctx.degrade(SearchComponent.ODSAY, DegradeReason.NO_DATA);
            throw cause;
        }
        if (segment.isEmpty()) {
            log.warn("[Route] -98 fallback TMAP 빈 응답(서비스지역 외) — 원래 ODsay 에러 전파");
            ctx.degrade(SearchComponent.WALK, DegradeReason.EMPTY_RESULT);
            ctx.degrade(SearchComponent.ODSAY, DegradeReason.NO_DATA);
            throw cause;
        }

        // 요청이 요구한 보강(도보 상세)이 완성된 응답 → outcome=FULL.
        ctx.requested(SearchComponent.WALK);
        return buildWalkOnlyResult(request, segment);
    }

    private RouteSearchResult buildWalkOnlyResult(RouteSearchRequest request, WalkSegment segment) {
        int totalTimeMin = Math.max(1, (int) Math.round(segment.totalTime() / 60.0));
        Integer walkSeconds = segment.totalTime() > 0 ? segment.totalTime() : null;

        SubPathResult walk = new SubPathResult(
                TRAFFIC_TYPE_WALK, totalTimeMin, (double) segment.totalDistance(),
                List.of(), List.of(),
                request.startName(), request.endName(),
                request.startX(), request.startY(), request.endX(), request.endY(),
                null, null,            // trainType, payment
                null, null, null,      // startId, way, wayCode (지하철 전용)
                segment.polyline(),
                null, null, null, null, null, null,  // 지하철 출구 정보
                segment.steps(),
                null, null, null, null, null,        // ODsay 버스 식별자
                walkSeconds,
                null                   // transferArrivals
        );
        PathInfo info = new PathInfo(
                totalTimeMin, 0, segment.totalDistance(), 0,
                request.startName(), request.endName(), null);
        PathResult path = new PathResult(PATH_TYPE_WALK_ONLY, info, List.of(walk), List.of());
        return new RouteSearchResult(0, 0, 0, 0, 0, List.of(path));
    }
}
