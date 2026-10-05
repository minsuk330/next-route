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
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
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
import java.util.Optional;
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
    private final WalkSegmentEnricher walkSegmentEnricher;
    private final TransferArrivalEnricher transferArrivalEnricher;
    private final RouteSearchMetrics metrics;
    private final RouteSearchDeadlineProperties deadlineProperties;
    // provider 전환(national-otp P2). 기본 ODSAY 라 아래 branch 는 default 에서 실행되지 않는다.
    private final RoutingProviderSelector providerSelector;
    private final LegacyRouteResponseMapper legacyRouteResponseMapper;
    private final DepartureTimeResolver departureTimeResolver;
    // OTP 글로벌 동시성 상한. otp-enabled 전엔 아래 branch 를 안 타므로 dormant.
    private final OtpConcurrencyGate otpConcurrencyGate;
    private final Clock clock;

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
            // OTP 호출 자체는 전용 otpMs 를 쓴다(다단계 예산 ctx.budgetMillis 대신). 하나의 deadline 을
            // gate 대기와 OTP 호출이 공유한다(예산 이중소비 금지). 그 뒤 붙는 도착예측 보강은 ODSAY
            // 분기와 똑같이 ctx 의 남은 예산 안에서 돈다.
            RoutingDeadline deadline = RoutingDeadline.of(clock, deadlineProperties.getOtpMs());
            Optional<OtpPermit> permit = otpConcurrencyGate.tryAcquire(deadline, "api");
            if (permit.isEmpty()) {
                // 포화·Redis 장애(fail-closed) → 504. ODSAY 로 provider 를 섞지 않는다(결과 일관성).
                return handleOtpSaturated(ctx);
            }
            try {
                return runOtpSearch(request, ctx, deadline, withRealtime, searchStartedAt);
            } finally {
                otpConcurrencyGate.release(permit.get());
            }
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

        // 1. TMAP 보행자 경로 보강 (trafficType=3)
        RouteSearchResult afterWalk = result;
        result = stage(ctx, SearchComponent.WALK,
                () -> walkSegmentEnricher.enrich(afterWalk,
                        request.startX(), request.startY(), request.startName(),
                        request.endX(), request.endY(), request.endName(), ctx,
                        deadlineProperties));

        // 2. 환승 도착예측 보강 (trafficType=2 버스) — baseline(STATIC)은 생략해 fan-out 낭비 방지.
        // withRealtime=false 는 요청이 실시간을 요구하지 않은 것이므로 degradation 이 아니다(단계 미적용).
        if (withRealtime) {
            RouteSearchResult afterBus = result;
            result = stage(ctx, SearchComponent.BUS_REALTIME,
                    () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx));
        }

        return result;
    }

    /**
     * OTP provider 경로. RoutingPort 로 검색해 중립 결과를 legacy 응답으로 매핑하고, 버스 구간에
     * 도착예측을 채운다.
     *
     * <p>지하철 폴리라인(ODsay 캐시)·TMAP 도보 상세는 타지 않는다 — 대중교통 형상은 P3(OSM relation)이
     * 이미 채우고, 도보는 OTP 가 계산한 실경로를 쓴다. <b>도착예측만</b> ODSAY 분기와 같은
     * {@link TransferArrivalEnricher} 를 태운다(2026-09-20 계약 변경: 서버가 응답에 실어 내린다).
     */
    private RouteSearchResult runOtpSearch(RouteSearchRequest request, RouteSearchContext ctx,
                                           RoutingDeadline deadline, boolean withRealtime,
                                           Instant searchStartedAt) {
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
        RouteSearchResult mapped = legacyRouteResponseMapper.toLegacy((RoutingResult) outcome);
        // 요청이 실시간을 요구하지 않았으면(알림용 STATIC 경로) 보강하지 않는다 — degradation 이 아니다.
        if (!withRealtime) {
            return mapped;
        }
        // 버스 구간 도착예측. enricher 는 값만 채운다 — 엔진이 준 순서(가장 빠른 것 먼저)를 그대로 두고
        // 재정렬·강등·MISSED 숨김은 하지 않는다(그 판단은 사용자 몫). 보강이 예산에 잘리면
        // transferArrivals 가 비고 meta.degraded 에 bus_realtime 이 남는다 — 검색 자체는 200 이다.
        RouteSearchResult afterBus = mapped;
        return stage(ctx, SearchComponent.BUS_REALTIME,
                () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx));
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

    /**
     * OTP 동시성 상한 포화(또는 Redis fail-closed). 기존 TIMEOUT 계약을 재사용해 504 로 낸다 —
     * ODSAY 폴백으로 provider 를 섞지 않는다(요청별 결과 일관성). "2xx degraded body" 가 아니다.
     */
    private RouteSearchResult handleOtpSaturated(RouteSearchContext ctx) {
        ctx.degrade(SearchComponent.ODSAY, DegradeReason.TIMEOUT);
        metrics.recordDeadlineExceeded(SearchComponent.ODSAY);
        throw OdSayApiException.timeout("OTP semaphore saturated");
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
