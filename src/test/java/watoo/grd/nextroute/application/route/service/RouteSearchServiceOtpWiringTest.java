package watoo.grd.nextroute.application.route.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.dto.RouteSearchRequest;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.exception.OdSayApiException;
import watoo.grd.nextroute.application.route.observability.RouteSearchMetrics;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.LegGeometry;
import watoo.grd.nextroute.application.route.routing.RealtimeCoverage;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingPort;
import watoo.grd.nextroute.application.route.routing.RoutingProvider;
import watoo.grd.nextroute.application.route.routing.RoutingProviderSelector;
import watoo.grd.nextroute.application.route.routing.RoutingQuery;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/** OTP 배선: default-provider=OTP+otp-enabled 일 때만 OTP 경로, ODsay 미호출. 기본(ODSAY)은 별도 테스트. */
class RouteSearchServiceOtpWiringTest {

    private final OdSayApiPort odsayNeverCalled = new OdSayApiPort() {
        @Override public RouteSearchResult searchPath(double sx, double sy, double ex, double ey, long t) {
            throw new AssertionError("OTP 경로에서 ODsay 가 호출되면 안 됨");
        }
    };

    /** permit 을 항상 내어주는 gate — 이 테스트는 gate 가 아니라 OTP 매핑/실패 변환을 본다. */
    private final OtpConcurrencyGate grantingGate = new OtpConcurrencyGate() {
        @Override public Optional<OtpPermit> tryAcquire(RoutingDeadline d, String role) {
            return Optional.of(new OtpPermit("t"));
        }
        @Override public void release(OtpPermit permit) { }
    };

    private RouteSearchService service(RoutingPort otpPort) {
        // 보강기가 받은 결과를 그대로 돌려주게 둔다 — 이 헬퍼를 쓰는 시험은 매핑·실패 변환을 본다.
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any()))
                .willAnswer(call -> call.getArgument(0));
        return service(otpPort, enricher);
    }

    private RouteSearchService service(RoutingPort otpPort, TransferArrivalEnricher enricher) {
        return service(otpPort, enricher, limitFive());
    }

    private RouteSearchService service(RoutingPort otpPort, TransferArrivalEnricher enricher,
                                       watoo.grd.nextroute.application.route.config.OtpRoutingProperties otpProps) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        RoutingProviderSelector selector = new RoutingProviderSelector(props, List.of(otpPort));
        return new RouteSearchService(
                odsayNeverCalled, mock(watoo.grd.nextroute.application.route.port.out.TmapPedestrianPort.class),
                mock(WalkSegmentEnricher.class),
                enricher,
                new RouteSearchMetrics(new SimpleMeterRegistry()),
                new RouteSearchDeadlineProperties(),
                selector,
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)),
                new DepartureTimeResolver(Clock.systemDefaultZone()), grantingGate, Clock.systemDefaultZone(), otpProps,
                new WalkCoordResolver());
    }

    private RoutingPort otpReturning(RoutingOutcome outcome) {
        return new RoutingPort() {
            @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) { return outcome; }
            @Override public RoutingProvider provider() { return RoutingProvider.OTP; }
        };
    }

    /** 버스 한 구간짜리 OTP 결과. 여러 시험이 같은 모양을 쓴다. */
    private RoutingResult busItinerary() {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R", "143",
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 126.98, 37.55, "정류장1")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(bus));
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), List.of(it));
    }

    @Test
    @DisplayName("OTP 경로: RoutingResult → legacy 응답, ODsay 미호출")
    void otpPathMapsToLegacy() {
        RouteSearchResult legacy = service(otpReturning(busItinerary()))
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(legacy.paths()).hasSize(1);
        assertThat(legacy.paths().get(0).subPaths().get(0).trafficType()).isEqualTo(2); // BUS
        assertThat(legacy.paths().get(0).info().payment()).isEqualTo(-1);              // UNKNOWN→-1
    }

    @Test
    @DisplayName("OTP 후보는 비용 순으로 줄 세워 5개만 — 뒤쪽 저비용 지하철이 1위로 올라온다")
    void otpCandidatesRankedByCostAndCapped() {
        RouteSearchResult legacy = service(otpReturning(busesThenCheapSubway()))
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(legacy.paths()).hasSize(5);
        assertThat(legacy.paths().get(0).subPaths().get(0).trafficType()).isEqualTo(1); // SUBWAY
    }

    @Test
    @DisplayName("정렬 킬스위치를 끄면 엔진 순서 앞 5개 — 옛 동작")
    void rankingDisabledKeepsEngineOrder() {
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> call.getArgument(0));
        watoo.grd.nextroute.application.route.config.OtpRoutingProperties otpProps = limitFive();
        otpProps.setRankingEnabled(false);

        RouteSearchResult legacy = service(otpReturning(busesThenCheapSubway()), enricher, otpProps)
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(legacy.paths()).hasSize(5);
        assertThat(legacy.paths()).allSatisfy(p -> assertThat(p.subPaths().get(0).trafficType()).isEqualTo(2));
    }

    @Test
    @DisplayName("고유 경로가 5개 미만이면 지하철만 → 버스만을 더 물어 5개를 채운다 — 같은 경로 반복은 없다")
    void shortResultSupplementsRailThenBus() {
        ScriptedOtp otp = new ScriptedOtp()
                .on(Set.of(), result(singleLeg(RoutingMode.SUBWAY, "1:L1", "서울1호선", 4169),
                        singleLeg(RoutingMode.SUBWAY, "1:L1", "서울1호선", 4169)))
                .on(RAIL_ONLY, result(singleLeg(RoutingMode.SUBWAY, "1:L1X", "서울1호선", 4100),
                        singleLeg(RoutingMode.RAIL, "1:KTX", "KTX", 4300)))
                .on(BUS_ONLY, result(singleLeg(RoutingMode.BUS, "1:1400", "1400", 6000),
                        singleLeg(RoutingMode.BUS, "1:88", "88", 6100),
                        singleLeg(RoutingMode.BUS, "1:586", "586", 6200),
                        singleLeg(RoutingMode.BUS, "1:581", "581", 6300)));

        RouteSearchResult legacy = service(otp).search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(otp.asked).containsExactly(Set.of(), RAIL_ONLY, BUS_ONLY);
        assertThat(firstLaneNames(legacy)).containsExactly("서울1호선", "KTX", "1400", "88", "586");
    }

    @Test
    @DisplayName("본 검색이 고유 5개면 더 묻지 않는다")
    void fullResultSkipsSupplementary() {
        ScriptedOtp otp = new ScriptedOtp().on(Set.of(), busesThenCheapSubway());

        service(otp).search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(otp.asked).containsExactly(Set.of());
    }

    @Test
    @DisplayName("지하철만으로 5개가 차면 버스는 묻지 않는다")
    void railFillsSoBusIsSkipped() {
        ScriptedOtp otp = new ScriptedOtp()
                .on(Set.of(), result(singleLeg(RoutingMode.BUS, "1:146", "146", 4500),
                        singleLeg(RoutingMode.BUS, "1:3100", "3100", 4600)))
                .on(RAIL_ONLY, result(singleLeg(RoutingMode.SUBWAY, "1:S4", "서울4호선", 5000),
                        singleLeg(RoutingMode.SUBWAY, "1:S7", "서울7호선", 5100),
                        singleLeg(RoutingMode.SUBWAY, "1:S3", "서울3호선", 5200)));

        RouteSearchResult legacy = service(otp).search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(otp.asked).containsExactly(Set.of(), RAIL_ONLY);
        assertThat(legacy.paths()).hasSize(5);
    }

    @Test
    @DisplayName("추가 검색이 실패해도 본 결과로 응답한다")
    void supplementaryFailureKeepsMainResult() {
        ScriptedOtp otp = new ScriptedOtp()
                .on(Set.of(), result(singleLeg(RoutingMode.BUS, "1:A", "A", 4000),
                        singleLeg(RoutingMode.BUS, "1:B", "B", 4100)))
                .on(RAIL_ONLY, RoutingFailure.of(RoutingFailure.Kind.NO_ROUTE, "NO_TRANSIT_CONNECTION", "x"))
                .on(BUS_ONLY, RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "TIMEOUT", "x"));

        RouteSearchResult legacy = service(otp).search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(otp.asked).containsExactly(Set.of(), RAIL_ONLY, BUS_ONLY);
        assertThat(firstLaneNames(legacy)).containsExactly("A", "B");
    }

    @Test
    @DisplayName("추가 검색 킬스위치를 끄면 본 검색만")
    void supplementaryDisabled() {
        ScriptedOtp otp = new ScriptedOtp()
                .on(Set.of(), result(singleLeg(RoutingMode.BUS, "1:A", "A", 4000)));
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> call.getArgument(0));
        watoo.grd.nextroute.application.route.config.OtpRoutingProperties otpProps = limitFive();
        otpProps.setSupplementaryEnabled(false);

        service(otp, enricher, otpProps).search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(otp.asked).containsExactly(Set.of());
    }

    /** 정렬·추가 검색 시험은 노출 상한 5 로 고정해 본다(기본값 10 은 아래 시험이 따로 본다). */
    private static watoo.grd.nextroute.application.route.config.OtpRoutingProperties limitFive() {
        watoo.grd.nextroute.application.route.config.OtpRoutingProperties p = new watoo.grd.nextroute.application.route.config.OtpRoutingProperties();
        p.setDisplayLimit(5);
        return p;
    }

    @Test
    @DisplayName("기본 노출 상한은 검색 10개 — 고유 12개 후보에서 비용 순 10개(재탐색 상한 5 와 별개)")
    void defaultDisplayLimitIsTen() {
        java.util.List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            engine.add(singleLeg(RoutingMode.BUS, "1:R" + i, "B" + i, 6000 - i));
        }
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> call.getArgument(0));
        watoo.grd.nextroute.application.route.config.OtpRoutingProperties defaults = new watoo.grd.nextroute.application.route.config.OtpRoutingProperties();

        RouteSearchResult legacy = service(otpReturning(new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), engine)), enricher, defaults)
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(defaults.getDisplayLimit()).isEqualTo(10);
        assertThat(defaults.getRerouteLimit()).isEqualTo(5);
        assertThat(firstLaneNames(legacy)).containsExactly("B11", "B10", "B9", "B8", "B7", "B6", "B5", "B4", "B3", "B2");
    }

    @Test
    @DisplayName("기본값: 본 검색 고유 6개면 추가 검색 없이 6개 그대로 — 5개로 줄이지 않고, 추가 검색 기준은 예전처럼 5")
    void defaultKeepsSixWithoutSupplementary() {
        java.util.List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            engine.add(singleLeg(RoutingMode.BUS, "1:R" + i, "B" + i, 5000 + i));
        }
        ScriptedOtp otp = new ScriptedOtp().on(Set.of(), new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), engine));
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> call.getArgument(0));
        watoo.grd.nextroute.application.route.config.OtpRoutingProperties defaults = new watoo.grd.nextroute.application.route.config.OtpRoutingProperties();

        RouteSearchResult legacy = service(otp, enricher, defaults)
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(defaults.getSupplementaryThreshold()).isEqualTo(5);
        assertThat(otp.asked).containsExactly(Set.of());
        assertThat(firstLaneNames(legacy)).containsExactly("B0", "B1", "B2", "B3", "B4", "B5");
    }

    private static final Set<RoutingMode> RAIL_ONLY = Set.of(RoutingMode.SUBWAY, RoutingMode.RAIL);
    private static final Set<RoutingMode> BUS_ONLY = Set.of(RoutingMode.BUS);

    /** 수단 제한별로 정해 둔 결과를 주고, 받은 수단 제한을 차례로 적어 둔다. 정하지 않은 제한은 무경로. */
    private static final class ScriptedOtp implements RoutingPort {
        private final java.util.Map<Set<RoutingMode>, RoutingOutcome> byModes = new java.util.HashMap<>();
        private final java.util.List<Set<RoutingMode>> asked = new java.util.ArrayList<>();

        ScriptedOtp on(Set<RoutingMode> modes, RoutingOutcome outcome) {
            byModes.put(modes, outcome);
            return this;
        }

        @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) {
            asked.add(q.modes());
            return byModes.getOrDefault(q.modes(),
                    RoutingFailure.of(RoutingFailure.Kind.NO_ROUTE, "NO_TRANSIT_CONNECTION", "scripted"));
        }

        @Override public RoutingProvider provider() { return RoutingProvider.OTP; }
    }

    private RoutingResult result(RoutingItinerary... its) {
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), List.of(its));
    }

    private static List<String> firstLaneNames(RouteSearchResult legacy) {
        return legacy.paths().stream()
                .map(p -> {
                    var lane = p.subPaths().get(0).lanes().get(0);
                    return lane.name() != null ? lane.name() : lane.busNo();
                })
                .toList();
    }

    /** OTP 기본 순서 재현: 서로 다른 버스 6개가 앞, 비용이 가장 낮은 지하철이 7번째. */
    private RoutingResult busesThenCheapSubway() {
        java.util.List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            engine.add(singleLeg(RoutingMode.BUS, "1:R" + i, "B" + i, 5000 + i));
        }
        engine.add(singleLeg(RoutingMode.SUBWAY, "1:S2", "서울2호선", 3557));
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), engine);
    }

    private RoutingItinerary singleLeg(RoutingMode mode, String routeId, String routeName, int cost) {
        RoutingLeg leg = new RoutingLeg(mode, mode.name(), "1", "1:A1", routeId, routeName,
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 126.98, 37.55, "정류장1")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        return new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(leg), cost);
    }

    @Test
    @DisplayName("실시간 요청이면 버스 도착예측을 서버가 채워 내린다 — 앱이 따로 묻지 않는다")
    void otpPathEnrichesTransferArrivals() {
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        java.util.concurrent.atomic.AtomicReference<RouteSearchResult> enriched = new java.util.concurrent.atomic.AtomicReference<>();
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> {
            RouteSearchResult in = call.getArgument(0);
            enriched.set(new RouteSearchResult(0, 0, 0, 0, 0, new java.util.ArrayList<>(in.paths())));
            return enriched.get();
        });

        RouteSearchResult out = service(otpReturning(busItinerary()), enricher)
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        verify(enricher).enrich(any(), any(), any());
        // 보강기가 돌려준 결과가 그대로 응답이 된다(meta 만 감싼다).
        assertThat(out.paths()).isSameAs(enriched.get().paths());
    }

    @Test
    @DisplayName("후보가 전부 운행종료로 빠지면 422 ROUTE_ALL_ENDED — 빈 200 으로 '경로 없음'과 섞지 않는다(#400)")
    void allEndedIs422() {
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        given(enricher.enrich(any(), any(), any())).willReturn(new RouteSearchResult(0, 0, 0, 0, 0, List.of()));

        RouteSearchService svc = service(otpReturning(busItinerary()), enricher);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> svc.search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d")))
                .isInstanceOf(watoo.grd.nextroute.global.error.BusinessException.class)
                .extracting(e -> ((watoo.grd.nextroute.global.error.BusinessException) e).getErrorCode())
                .isEqualTo(watoo.grd.nextroute.global.error.code.RouteErrorCode.ROUTE_ALL_ENDED);
    }

    @Test
    @DisplayName("실시간을 요구하지 않은 요청(알림 STATIC)은 보강하지 않는다")
    void withoutRealtimeSkipsEnricher() {
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);

        RouteSearchResult out = service(otpReturning(busItinerary()), enricher)
                .searchWithoutRealtime(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        verifyNoInteractions(enricher);
        assertThat(out.paths()).hasSize(1);
    }

    @Test
    @DisplayName("보강이 값을 못 채워도 검색은 성공하고 순서도 그대로다")
    void enricherMissKeepsSearchAlive() {
        TransferArrivalEnricher enricher = mock(TransferArrivalEnricher.class);
        // 보강기가 손대지 않고 그대로 돌려주는 경우(예산 초과·provider 실패).
        given(enricher.enrich(any(), any(), any())).willAnswer(call -> call.getArgument(0));

        RouteSearchResult out = service(otpReturning(busItinerary()), enricher)
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));

        assertThat(out.paths()).hasSize(1);
        assertThat(out.paths().get(0).subPaths().get(0).trafficType()).isEqualTo(2);
        assertThat(out.paths().get(0).subPaths().get(0).transferArrivals()).isNull();
    }

    @Test
    @DisplayName("OTP OUT_OF_SCOPE → 빈 결과(무경로), TIMEOUT → 504 신호")
    void otpFailures() {
        RouteSearchResult empty = service(otpReturning(
                RoutingFailure.of(RoutingFailure.Kind.OUT_OF_SCOPE, "OUTSIDE_BOUNDS", "밖")))
                .search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d"));
        assertThat(empty.paths()).isEmpty();

        RouteSearchService svc = service(otpReturning(
                RoutingFailure.of(RoutingFailure.Kind.TIMEOUT, "T", "느림")));
        assertThatThrownBy(() -> svc.search(new RouteSearchRequest(126.9, 37.5, 127.0, 37.4, "o", "d")))
                .isInstanceOf(OdSayApiException.class)
                .satisfies(e -> assertThat(((OdSayApiException) e).getKind())
                        .isEqualTo(OdSayApiException.Kind.TIMEOUT));
    }

    // ── 도보 구간 끝점(#468) ────────────────────────────────────────────────

    /** OTP 도보 leg: 정류장 목록이 없다(OtpPlanConnectionMapper 가 걷기에는 만들지 않는다). */
    private static RoutingLeg otpWalkLeg() {
        return new RoutingLeg(RoutingMode.WALK, null, null, null, null, null, null, null,
                Instant.now(), Instant.now(), 300, List.of(), List.of(), LegGeometry.missing("walk"),
                Enrichment.staticOnly(RealtimeCoverage.NONE), Set.of());
    }

    private static RoutingLeg otpSubwayLeg(StopOccurrence board, StopOccurrence alight) {
        return new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", "1:A1", "1:S2", "서울2호선",
                "1:T", null, Instant.now(), Instant.now(), 600, List.of(board, alight),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
    }

    private static RoutingResult itinerary(RoutingLeg... legs) {
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 600, 0, false,
                List.of(), Fare.unknown(), List.of(legs));
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g1",
                null, null, Instant.now(), List.of(), List.of(it));
    }

    @Test
    @DisplayName("장소 목적지 OTP 경로: 첫 걷기 시작·마지막 걷기 끝이 요청 출발·도착 이름과 좌표다(#468)")
    void otpPlaceDestinationFillsWalkEndpoints() {
        StopOccurrence gangnam = new StopOccurrence("1:RS_222", null, null, 0, 127.0276, 37.4979, "강남");
        StopOccurrence samseong = new StopOccurrence("1:RS_219", null, null, 1, 127.0630, 37.5088, "삼성");

        RouteSearchResult legacy = service(otpReturning(itinerary(
                otpWalkLeg(), otpSubwayLeg(gangnam, samseong), otpWalkLeg())))
                .search(new RouteSearchRequest(127.0270, 37.4985, 127.0590, 37.5115, "현재 위치", "코엑스"));

        List<watoo.grd.nextroute.application.route.dto.SubPathResult> subs = legacy.paths().get(0).subPaths();
        assertThat(subs).extracting(s -> s.trafficType()).containsExactly(3, 1, 3);
        assertThat(subs.get(0).startName()).isEqualTo("현재 위치");
        assertThat(subs.get(0).startX()).isEqualTo(127.0270);
        assertThat(subs.get(0).endName()).isEqualTo("강남");
        assertThat(subs.get(2).startName()).isEqualTo("삼성");
        assertThat(subs.get(2).endName()).isEqualTo("코엑스");
        assertThat(subs.get(2).endX()).isEqualTo(127.0590);
        assertThat(subs.get(2).endY()).isEqualTo(37.5115);
        assertThat(subs.get(1).startName()).isEqualTo("강남");
        assertThat(subs.get(1).endName()).isEqualTo("삼성");
    }

    @Test
    @DisplayName("역 목적지 OTP 경로는 하차역에서 끝난다 — 실시간 없는 검색도 같은 규칙으로 걷기만 채운다")
    void otpStationDestinationKeepsTransitEnd() {
        StopOccurrence seoul = new StopOccurrence("1:RS_150", null, null, 0, 126.9707, 37.5547, "서울역");
        StopOccurrence cityHall = new StopOccurrence("1:RS_151", null, null, 1, 126.9769, 37.5657, "시청");

        RouteSearchResult legacy = service(otpReturning(itinerary(otpWalkLeg(), otpSubwayLeg(seoul, cityHall))))
                .searchWithoutRealtime(new RouteSearchRequest(126.9700, 37.5550, 126.9769, 37.5657,
                        "현재 위치", "시청역"));

        List<watoo.grd.nextroute.application.route.dto.SubPathResult> subs = legacy.paths().get(0).subPaths();
        assertThat(subs).extracting(s -> s.trafficType()).containsExactly(3, 1);
        assertThat(subs.get(0).startName()).isEqualTo("현재 위치");
        assertThat(subs.get(0).endName()).isEqualTo("서울역");
        assertThat(subs.get(1).endName()).isEqualTo("시청");
        assertThat(subs.get(1).endX()).isEqualTo(126.9769);
    }
}
