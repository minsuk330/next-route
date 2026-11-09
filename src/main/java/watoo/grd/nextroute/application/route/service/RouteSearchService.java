package watoo.grd.nextroute.application.route.service;

import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.RouteErrorCode;
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
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
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
import watoo.grd.nextroute.application.route.routing.ExpressFilterPolicy;
import watoo.grd.nextroute.application.route.routing.ItineraryRanker;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.subway.dto.TrainKind;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    // 후보 수·노출 상한·정렬 킬스위치(ItineraryRanker).
    private final OtpRoutingProperties otpRoutingProperties;
    // OTP 도보 구간 끝점 이름·좌표(#468). ODSAY 분기는 WalkSegmentEnricher 가 같은 규칙을 쓴다.
    private final WalkCoordResolver walkCoordResolver;
    // 급행 끄기·DIRECT 제외(subway-express S2). 재탐색과 같은 정책 객체.
    private final ExpressFilterPolicy expressFilterPolicy;

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
            result = requireRideable(afterBus, stage(ctx, SearchComponent.BUS_REALTIME,
                    () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx)));
        }

        return result;
    }

    /**
     * OTP provider 경로. RoutingPort 로 검색해 중립 결과를 legacy 응답으로 매핑하고, 버스 구간에
     * 도착예측을 채운다.
     *
     * <p>지하철 폴리라인(ODsay 캐시)·TMAP 도보 상세는 타지 않는다 — 대중교통 형상은 P3(OSM relation)이
     * 이미 채우고, 도보는 OTP 가 계산한 실경로를 쓴다(도보 끝점 이름·좌표만 {@link WalkCoordResolver} 로 채운다).
     * <b>도착예측만</b> ODSAY 분기와 같은
     * {@link TransferArrivalEnricher} 를 태운다(2026-09-20 계약 변경: 서버가 응답에 실어 내린다).
     */
    private RouteSearchResult runOtpSearch(RouteSearchRequest request, RouteSearchContext ctx,
                                           RoutingDeadline deadline, boolean withRealtime,
                                           Instant searchStartedAt) {
        Instant departureAt = departureTimeResolver.resolve(request.departureAt());
        boolean excludeExpress = request.excludesExpress();
        RoutingQuery query = new RoutingQuery(
                request.startX(), request.startY(), request.startName(),
                request.endX(), request.endY(), request.endName(),
                departureAt, java.util.Set.of(), null, false, "otp-v2",
                RoutingProvider.OTP, null, expressFilterPolicy.routeExclusions(excludeExpress));

        long start = System.nanoTime();
        RoutingOutcome outcome;
        try {
            outcome = providerSelector.portFor(RoutingProvider.OTP).search(query, deadline);
            if (outcome instanceof RoutingResult first) {
                // 후보 풀은 사후 필터 전 값으로 모은다(DIRECT 만 언제나 뺀다) — 0건 해제(D10)가 풀 값으로 돌아간다.
                List<RoutingItinerary> pool = new ArrayList<>(expressFilterPolicy.filter(first.itineraries(), false));
                supplementIfShort(pool, query, deadline, excludeExpress);
                if (!excludeExpress) {
                    recoverNormalAlternatives(pool, query, deadline);
                }
                outcome = first.withItineraries(applyExpressFilter(pool, query, deadline, excludeExpress, ctx));
            }
        } finally {
            metrics.recordStage(SearchComponent.ODSAY, ctx.isDegraded(SearchComponent.ODSAY),
                    System.nanoTime() - start);
        }

        if (outcome instanceof RoutingFailure failure) {
            return handleOtpFailure(failure, ctx);
        }
        // 도보 구간 이름·좌표는 ODSAY 분기와 같은 규칙으로 요청·인접 역에서 채운다(TMAP 없이, #468). OTP 도보 구간은
        // 정류장 목록이 없어 비어 나온다. 이 값이 검색 스냅샷에 실려 여정 단계의 첫·끝 걷기 이름이 된다.
        RouteSearchResult mapped = walkCoordResolver.fillWalkEndpoints(
                legacyRouteResponseMapper.toLegacy(selectCandidates((RoutingResult) outcome)),
                request.startX(), request.startY(), request.startName(),
                request.endX(), request.endY(), request.endName());
        // 요청이 실시간을 요구하지 않았으면(알림용 STATIC 경로) 보강하지 않는다 — degradation 이 아니다.
        if (!withRealtime) {
            return mapped;
        }
        // 버스 구간 도착예측. enricher 는 값을 채우고, 실시간이 "운행종료"라 한 버스 구간을 지나는 경로만 뺀다(#400) —
        // 남은 순서는 그대로, 놓침(MISSED)·지연 기반 재정렬·강등은 하지 않는다(그 판단은 사용자 몫). 보강이 예산에 잘리면
        // transferArrivals 가 비고 meta.degraded 에 bus_realtime 이 남는다 — 검색 자체는 200 이다.
        RouteSearchResult afterBus = mapped;
        return requireRideable(afterBus, stage(ctx, SearchComponent.BUS_REALTIME,
                () -> transferArrivalEnricher.enrich(afterBus, searchStartedAt, ctx)));
    }

    /** 후보가 있었는데 전부 운행종료로 빠졌으면 422 — 앱이 "지금은 탈 수 있는 경로가 없어요"를 보인다(#400). */
    private static RouteSearchResult requireRideable(RouteSearchResult before, RouteSearchResult after) {
        boolean hadPaths = before.paths() != null && !before.paths().isEmpty();
        boolean nonePaths = after.paths() == null || after.paths().isEmpty();
        if (hadPaths && nonePaths) {
            throw new BusinessException(RouteErrorCode.ROUTE_ALL_ENDED);
        }
        return after;
    }

    /** 고유 경로가 모자랄 때 차례로 더 묻는 수단 제한. 지하철만이 싸서(0.05~0.6s) 먼저, 버스만은 0.4~1.3s. */
    private static final List<Set<RoutingMode>> SUPPLEMENTARY_MODES = List.of(
            Set.of(RoutingMode.SUBWAY, RoutingMode.RAIL), Set.of(RoutingMode.BUS));

    /**
     * 본 검색의 고유 경로가 노출 상한보다 적으면 수단을 제한해 더 묻고 후보를 이어 붙인다. OTP 는 지배당한 대안
     * (더 늦게 도착·환승 많음·비용 높음)을 탐색 단계에서 버려 부평→서울은 1호선 하나만 남는다 — 수단별로 물으면
     * 버스 대안이 살아난다(2026-10-12 실측 12개). 같은 permit 안에서 순차로 묻고, 한 번에 쓸 예산은 상한을 둔다.
     * 추가 검색 실패는 무시한다(본 결과만으로도 응답은 성립한다).
     *
     * <p>고유 경로 수는 급행 사후 필터 <b>뒤</b> 값이다(subway-express S2) — 급행만 남는 경로를 세면 급행 끄기에서 추가
     * 검색을 건너뛴다. 추가 검색도 본 검색의 제외 노선을 그대로 들고 간다({@link RoutingQuery#withModes}).
     *
     * @param pool 사후 필터 전 후보(DIRECT 제외). 여기에 이어 붙인다
     */
    private void supplementIfShort(List<RoutingItinerary> pool, RoutingQuery query, RoutingDeadline deadline,
                                   boolean excludeExpress) {
        // 기준은 노출 상한이 아니라 추가 검색 기준(5) — 노출을 늘려도 OTP 추가 호출은 예전과 같다.
        int limit = otpRoutingProperties.getSupplementaryThreshold();
        if (!otpRoutingProperties.isRankingEnabled() || !otpRoutingProperties.isSupplementaryEnabled()) {
            return;
        }
        int distinct = distinctAfterFilter(pool, limit, excludeExpress);
        for (Set<RoutingMode> modes : SUPPLEMENTARY_MODES) {
            if (distinct >= limit) {
                break;
            }
            Optional<RoutingDeadline> budget = optionalBudget(deadline);
            if (budget.isEmpty()) {
                break;
            }
            RoutingOutcome extra;
            try {
                extra = providerSelector.portFor(RoutingProvider.OTP).search(query.withModes(modes), budget.get());
            } catch (RuntimeException e) {
                log.warn("[OTP] 추가 검색 {} 실패: {}", modes, e.toString());
                continue;
            }
            int before = distinct;
            if (extra instanceof RoutingResult more) {
                pool.addAll(expressFilterPolicy.filter(more.itineraries(), false));
                distinct = distinctAfterFilter(pool, limit, excludeExpress);
            }
            log.info("[OTP] 고유 경로 {} < {} → 추가 검색 {}: {}, 합친 고유 {}", before, limit, modes,
                    extra instanceof RoutingResult more ? "후보 " + more.itineraries().size()
                            : ((RoutingFailure) extra).code(), distinct);
        }
    }

    private int distinctAfterFilter(List<RoutingItinerary> pool, int limit, boolean excludeExpress) {
        return ItineraryRanker.rank(expressFilterPolicy.filter(pool, excludeExpress), limit).size();
    }

    /** 추가 검색 한 번에 쓸 예산. 남은 OTP 예산이 선택 단계 하한 이하면 empty(묻지 않는다). */
    private Optional<RoutingDeadline> optionalBudget(RoutingDeadline deadline) {
        long remaining = deadline.remainingMillis(clock);
        if (remaining <= deadlineProperties.getMinRemainingForOptionalMs()) {
            return Optional.empty();
        }
        return Optional.of(RoutingDeadline.of(clock,
                Math.min(remaining, otpRoutingProperties.getSupplementaryBudgetMs())));
    }

    /**
     * 같은 노선의 일반 대안 되찾기(subway-express S2, D2). OTP 는 급행이 지배하면 일반 열차를 버린다(김포공항→고속터미널
     * 08:00 실측: 9호선 급행 4건만). 노출할 경로 중 <b>노선형 급행</b> 구간이 든 것마다 후보 풀에 같은 노선 흐름의 일반 짝이
     * 있는지 보고, 하나라도 없으면 <b>원래 수단 그대로</b>(버스 환승 포함) 노선형 급행 route 만 빼고 한 번 더 묻는다
     * (추가 검색과 같은 예산 규칙). 고유 경로 수가 넉넉해도 따로 검사한다.
     *
     * <p>정차 패턴형 급행(수인분당·경의중앙 등)은 OTP 가 노선 단위로만 거를 수 있어 되찾지 못한다(한계, PLAN §6-8).
     */
    private void recoverNormalAlternatives(List<RoutingItinerary> pool, RoutingQuery query, RoutingDeadline deadline) {
        if (!otpRoutingProperties.isRankingEnabled() || !otpRoutingProperties.isSupplementaryEnabled()) {
            return;
        }
        Set<String> expressRoutes = expressFilterPolicy.expressRouteIds();
        List<RoutingItinerary> shown = ItineraryRanker.rank(pool, otpRoutingProperties.getDisplayLimit());
        Optional<RoutingItinerary> unpaired = shown.stream()
                .filter(it -> it.legs().stream().anyMatch(leg -> isRouteTypeExpress(leg, expressRoutes)))
                .filter(it -> !hasNormalPair(it, pool))
                .findFirst();
        if (unpaired.isEmpty()) {
            return;
        }
        Optional<RoutingDeadline> budget = optionalBudget(deadline);
        if (budget.isEmpty()) {
            log.info("[OTP] 일반 대안 되찾기 생략 — 예산 부족");
            return;
        }
        RoutingOutcome extra;
        try {
            extra = providerSelector.portFor(RoutingProvider.OTP).search(
                    query.withExcludedRouteIds(expressRoutes), budget.get());
        } catch (RuntimeException e) {
            log.warn("[OTP] 일반 대안 되찾기 실패: {}", e.toString());
            return;
        }
        if (extra instanceof RoutingResult more) {
            pool.addAll(expressFilterPolicy.filter(more.itineraries(), false));
        }
        log.info("[OTP] 일반 대안 되찾기 {}: {}", ItineraryRanker.fingerprint(unpaired.get()),
                extra instanceof RoutingResult more ? "후보 " + more.itineraries().size()
                        : ((RoutingFailure) extra).code());
    }

    private static boolean isRouteTypeExpress(RoutingLeg leg, Set<String> expressRoutes) {
        return leg.trainKind() == TrainKind.EXPRESS && leg.sourceRouteId() != null
                && expressRoutes.contains(leg.sourceRouteId());
    }

    /**
     * 같은 노선 흐름(도보 뺀 구간마다 수단·노선 이름)에서 이 경로의 급행 구간 자리가 전부 급행이 아닌 경로가 풀에 있는가.
     */
    static boolean hasNormalPair(RoutingItinerary express, List<RoutingItinerary> pool) {
        List<RoutingLeg> legs = transitLegs(express);
        for (RoutingItinerary other : pool) {
            List<RoutingLeg> otherLegs = transitLegs(other);
            if (other == express || otherLegs.size() != legs.size()) {
                continue;
            }
            boolean pair = true;
            for (int i = 0; i < legs.size() && pair; i++) {
                RoutingLeg a = legs.get(i);
                RoutingLeg b = otherLegs.get(i);
                pair = a.mode() == b.mode() && java.util.Objects.equals(routeLabel(a), routeLabel(b))
                        && (a.trainKind() != TrainKind.EXPRESS || b.trainKind() != TrainKind.EXPRESS);
            }
            if (pair) {
                return true;
            }
        }
        return false;
    }

    private static List<RoutingLeg> transitLegs(RoutingItinerary it) {
        return it.legs().stream().filter(leg -> leg.mode() != RoutingMode.WALK).toList();
    }

    private static String routeLabel(RoutingLeg leg) {
        return leg.sourceRouteName() != null ? leg.sourceRouteName() : leg.sourceRouteId();
    }

    /**
     * 급행 끄기 사후 필터와 0건 해제(subway-express S2, D10). 급행을 켠 요청은 DIRECT 만 이미 빠진 풀 그대로다.
     *
     * <ul>
     *   <li>필터 뒤 남으면 그것 — 적용됨({@code requested(EXPRESS_FILTER)})</li>
     *   <li>필터 전에도 0건이면 진짜 무경로 — 아무 표시 없이 빈 결과</li>
     *   <li>필터로 0건이면 노선형 급행 route 를 빼고 <b>원래 수단</b>으로 한 번 더 묻는다. 거기서 일반 경로가 나오면 그것,
     *       그래도 0건이면 필터를 풀고 {@code express_filter}, 다시 묻기가 실패·시간 초과(예산 부족 포함)면 필터를 풀고
     *       {@code express_filter_unverified}</li>
     * </ul>
     */
    private List<RoutingItinerary> applyExpressFilter(List<RoutingItinerary> pool, RoutingQuery query,
                                                      RoutingDeadline deadline, boolean excludeExpress,
                                                      RouteSearchContext ctx) {
        if (!excludeExpress) {
            return pool;
        }
        List<RoutingItinerary> kept = expressFilterPolicy.filter(pool, true);
        if (!kept.isEmpty() || pool.isEmpty()) {
            if (!kept.isEmpty()) {
                ctx.requested(SearchComponent.EXPRESS_FILTER);
            }
            return kept;
        }
        Optional<RoutingDeadline> budget = optionalBudget(deadline);
        RoutingOutcome retry;
        if (budget.isEmpty()) {
            retry = RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "NO_BUDGET", "급행 끄기 다시 묻기 예산 부족");
        } else {
            try {
                retry = providerSelector.portFor(RoutingProvider.OTP).search(
                        query.withExcludedRouteIds(expressFilterPolicy.expressRouteIds()), budget.get());
            } catch (RuntimeException e) {
                retry = RoutingFailure.of(RoutingFailure.Kind.ENGINE_ERROR, "EXCEPTION", e.toString());
            }
        }
        if (retry instanceof RoutingResult more) {
            List<RoutingItinerary> normal = expressFilterPolicy.filter(more.itineraries(), true);
            if (!normal.isEmpty()) {
                log.info("[OTP] 급행 끄기 0건 → 다시 묻기에서 일반 경로 {}건", normal.size());
                ctx.requested(SearchComponent.EXPRESS_FILTER);
                return normal;
            }
            log.info("[OTP] 급행 끄기 0건 → 다시 묻기도 일반 경로 0건, 필터 해제");
            ctx.degrade(SearchComponent.EXPRESS_FILTER, DegradeReason.EMPTY_RESULT);
            return pool;
        }
        RoutingFailure failure = (RoutingFailure) retry;
        if (failure.kind() == RoutingFailure.Kind.NO_ROUTE || failure.kind() == RoutingFailure.Kind.OUT_OF_SCOPE) {
            // 무경로 답도 "일반 경로 0건"을 확인한 것이다 — 실패가 아니다.
            log.info("[OTP] 급행 끄기 0건 → 다시 묻기 무경로({}), 필터 해제", failure.code());
            ctx.degrade(SearchComponent.EXPRESS_FILTER, DegradeReason.EMPTY_RESULT);
            return pool;
        }
        log.info("[OTP] 급행 끄기 0건 → 다시 묻기 실패({}), 확인 못 한 채 필터 해제", failure.code());
        ctx.degrade(SearchComponent.EXPRESS_FILTER_UNVERIFIED,
                failure.kind() == RoutingFailure.Kind.TIMEOUT ? DegradeReason.TIMEOUT : DegradeReason.UPSTREAM_ERROR);
        return pool;
    }

    /**
     * OTP 후보 중 응답에 낼 경로를 고른다. OTP 기본 순서는 탐색 창 안 출발편의 도착시각 타임라인이라
     * 배차 잦은 버스가 상위를 채우고 같은 노선이 반복된다(2026-10-12 실측: 강남→홍대 상위 5개에 지하철 0개).
     * 정적 일반화비용으로만 줄 세운다 — 실시간 판단은 여기서 하지 않는다. 킬스위치가 꺼지면 엔진 순서 앞에서 자른다.
     */
    private RoutingResult selectCandidates(RoutingResult result) {
        int limit = otpRoutingProperties.getDisplayLimit();
        List<RoutingItinerary> its = result.itineraries();
        List<RoutingItinerary> chosen = otpRoutingProperties.isRankingEnabled()
                ? ItineraryRanker.rank(its, limit)
                : its.subList(0, Math.min(Math.max(limit, 0), its.size()));
        return result.withItineraries(chosen);
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
                null,                  // transferArrivals
                null,                  // trainKind(도보)
                null, null, null       // 계획 시각(도보)
        );
        PathInfo info = new PathInfo(
                totalTimeMin, 0, segment.totalDistance(), 0,
                request.startName(), request.endName(), null);
        PathResult path = new PathResult(PATH_TYPE_WALK_ONLY, info, List.of(walk), List.of());
        return new RouteSearchResult(0, 0, 0, 0, 0, List.of(path));
    }
}
