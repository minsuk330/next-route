package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Coord;
import watoo.grd.nextroute.application.journey.service.JourneyWaypointResolver.Waypoints;
import watoo.grd.nextroute.application.route.config.RoutingProviderProperties;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.routing.Enrichment;
import watoo.grd.nextroute.application.route.routing.Fare;
import watoo.grd.nextroute.application.route.routing.OtpConcurrencyGate;
import watoo.grd.nextroute.application.route.routing.OtpPermit;
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
import watoo.grd.nextroute.application.route.service.LegacyRouteResponseMapper;
import watoo.grd.nextroute.application.route.service.WalkCoordResolver;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 재탐색(RemainingRouteSearchService)의 OTP 경로. 기본 ODSAY 는 기존 테스트가 커버. */
class RemainingRouteSearchServiceOtpTest {

    private static final Waypoints WP = new Waypoints(new Coord(127.0, 37.5), new Coord(127.1, 37.4));
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-08T00:00:00Z"), ZoneOffset.UTC);

    private final OdSayApiPort odsayNeverCalled = new OdSayApiPort() {
        @Override public watoo.grd.nextroute.application.route.dto.RouteSearchResult searchPath(
                double sx, double sy, double ex, double ey, long t) {
            throw new AssertionError("OTP 재탐색에서 ODsay 호출 금지");
        }
    };

    private int otpCalls;
    private RoutingQuery lastQuery;

    private RemainingRouteSearchService service(RoutingOutcome otpOutcome) {
        RoutingProviderProperties props = new RoutingProviderProperties();
        props.setDefaultProvider(RoutingProvider.OTP);
        props.setOtpEnabled(true);
        RoutingPort otp = new RoutingPort() {
            @Override public RoutingOutcome search(RoutingQuery q, RoutingDeadline d) {
                otpCalls++;
                lastQuery = q;
                return otpOutcome;
            }
            @Override public RoutingProvider provider() { return RoutingProvider.OTP; }
        };
        OtpConcurrencyGate grantingGate = new OtpConcurrencyGate() {
            @Override public Optional<OtpPermit> tryAcquire(RoutingDeadline d, String role) {
                return Optional.of(new OtpPermit("t"));
            }
            @Override public void release(OtpPermit permit) { }
        };
        return new RemainingRouteSearchService(odsayNeverCalled,
                new JourneyRerouteMetrics(new SimpleMeterRegistry()),
                new RoutingProviderSelector(props, List.of(otp)),
                new LegacyRouteResponseMapper(mock(watoo.grd.nextroute.application.route.mapping.OtpSeoulIdentityResolver.class)), grantingGate, new watoo.grd.nextroute.application.route.config.RouteSearchDeadlineProperties(), clock, new watoo.grd.nextroute.application.route.config.OtpRoutingProperties(),
                new WalkCoordResolver(),
                new watoo.grd.nextroute.application.route.routing.ExpressFilterPolicy(
                        watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures.railServiceClassifier()));
    }

    @Test
    @DisplayName("OTP 성공 → legacy paths, ODsay 미호출")
    void otpSuccess() {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R", "143",
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 127.0, 37.5, "정류장")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false,
                List.of(), Fare.unknown(), List.of(bus));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), List.of(it));

        List<PathResult> paths = service(result).search("1:S1", WP, 2000);
        assertThat(paths).hasSize(1);
        assertThat(paths.get(0).subPaths().get(0).trafficType()).isEqualTo(2); // BUS
        // 경로가 1개뿐이어도 재탐색은 수단별 추가 검색을 하지 않는다(후보 수 = 실시간 평가 횟수).
        assertThat(otpCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("재탐색도 검색과 같은 정책으로 DIRECT 경로를 자르기 전에 뺀다(subway-express S2), 급행·열차 종류는 그대로 싣는다")
    void rerouteDropsDirectAndKeepsTrainKind() {
        List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (watoo.grd.nextroute.application.subway.dto.TrainKind kind : List.of(
                watoo.grd.nextroute.application.subway.dto.TrainKind.DIRECT,
                watoo.grd.nextroute.application.subway.dto.TrainKind.EXPRESS,
                watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL)) {
            RoutingLeg rail = new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", "1:A1", "1:R-" + kind, "서울9호선",
                    "1:T", null, Instant.now(), Instant.now(), 600,
                    List.of(new StopOccurrence("1:S1", null, null, 0, 127.0, 37.5, "가양"),
                            new StopOccurrence("1:S2", null, null, 1, 127.02, 37.49, "염창")),
                    List.of(), LegGeometry.missing("x"),
                    Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of()).withTrainKind(kind);
            engine.add(new RoutingItinerary(Instant.now(), 600, 0, 0, 0, false, List.of(), Fare.unknown(),
                    List.of(rail)));
        }
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), engine);

        List<PathResult> paths = service(result).search("1:S1", WP, 2000);

        assertThat(paths).extracting(p -> p.subPaths().get(0).trainKind()).containsExactly("EXPRESS", "NORMAL");
    }

    @Test
    @DisplayName("후보가 많아도 엔진 순서 앞 5개만(rerouteLimit, 검색 노출 10 과 별개) — 재탐색은 자체 ETA 정렬이 있어 비용으로 줄 세우지 않는다")
    void otpCandidatesCappedInEngineOrder() {
        List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (int i = 0; i < 7; i++) {
            RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R" + i, "B" + i,
                    "1:T", null, Instant.now(), Instant.now(), 600 + 60 * i,
                    List.of(new StopOccurrence("1:S1", null, null, 0, 127.0, 37.5, "정류장")),
                    List.of(), LegGeometry.missing("x"),
                    Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
            // 뒤로 갈수록 비용이 낮다 — 비용 정렬을 했다면 순서가 뒤집힌다.
            engine.add(new RoutingItinerary(Instant.now(), 600 + 60 * i, 0, 0, 0, false,
                    List.of(), Fare.unknown(), List.of(bus), 9000 - i));
        }
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), engine);

        List<PathResult> paths = service(result).search("1:S1", WP, 2000);

        assertThat(paths).extracting(p -> p.subPaths().get(0).sectionTime())
                .containsExactly(10, 11, 12, 13, 14);
    }

    @Test
    @DisplayName("OTP 걷기 구간도 이름·좌표를 채운다 — 첫 걷기는 원점, 버스 옆은 그 정류장, 마지막 걷기 끝은 원래 목적지(#474)")
    void otpWalksAreNamedFromOriginStopsAndDestination() {
        RoutingLeg bus = new RoutingLeg(RoutingMode.BUS, "BUS", "1", "1:A1", "1:R", "143",
                "1:T", null, Instant.now(), Instant.now(), 600,
                List.of(new StopOccurrence("1:S1", null, null, 0, 127.02, 37.48, "정류장1"),
                        new StopOccurrence("1:S2", null, null, 5, 127.08, 37.42, "정류장2")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
        RoutingItinerary it = new RoutingItinerary(Instant.now(), 600, 0, 300, 0, false,
                List.of(), Fare.unknown(), List.of(walkLeg(120), bus, walkLeg(180)));
        RoutingResult result = new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), List.of(it));
        Waypoints named = new Waypoints(new Coord(127.0, 37.5), new Coord(127.1, 37.4),
                "정류장A", "코엑스");

        List<SubPathResult> subs = service(result).search("1:S1", named, 2000).get(0).subPaths();

        SubPathResult first = subs.get(0);
        assertThat(first.trafficType()).isEqualTo(3);
        assertThat(first.startName()).isEqualTo("정류장A");
        assertThat(first.startX()).isEqualTo(127.0);
        assertThat(first.startY()).isEqualTo(37.5);
        assertThat(first.endName()).isEqualTo("정류장1");
        assertThat(first.endX()).isEqualTo(127.02);
        assertThat(first.endY()).isEqualTo(37.48);

        SubPathResult last = subs.get(2);
        assertThat(last.trafficType()).isEqualTo(3);
        assertThat(last.startName()).isEqualTo("정류장2");
        assertThat(last.startX()).isEqualTo(127.08);
        assertThat(last.startY()).isEqualTo(37.42);
        assertThat(last.endName()).isEqualTo("코엑스");
        assertThat(last.endX()).isEqualTo(127.1);
        assertThat(last.endY()).isEqualTo(37.4);
    }

    /** OTP 걷기 구간. 정류장이 없어 legacy 로 옮기면 이름·좌표가 비어 나온다. */
    private static RoutingLeg walkLeg(int seconds) {
        return new RoutingLeg(RoutingMode.WALK, "WALK", null, null, null, null,
                null, null, Instant.now(), Instant.now(), seconds,
                List.of(), List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of());
    }

    // ── 급행 끄기(subway-express S5) ─────────────────────────────────────────

    /** 9호선 가양→염창 한 구간 경로. 종류별로 노선 id 를 달리해 결과에서 구분한다. */
    private static RoutingItinerary rail(watoo.grd.nextroute.application.subway.dto.TrainKind kind, int seconds) {
        RoutingLeg leg = new RoutingLeg(RoutingMode.SUBWAY, "SUBWAY", "1", "1:A1", "1:R-" + kind + "-" + seconds,
                "서울9호선", "1:T", null, Instant.now(), Instant.now(), seconds,
                List.of(new StopOccurrence("1:S1", null, null, 0, 127.0, 37.5, "가양"),
                        new StopOccurrence("1:S2", null, null, 1, 127.02, 37.49, "염창")),
                List.of(), LegGeometry.missing("x"),
                Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), Set.of()).withTrainKind(kind);
        return new RoutingItinerary(Instant.now(), seconds, 0, 0, 0, false, List.of(), Fare.unknown(), List.of(leg));
    }

    private static RoutingResult otp(List<RoutingItinerary> itineraries) {
        return new RoutingResult(RoutingProvider.OTP, "v", "202503", "g",
                null, null, Instant.now(), List.of(), itineraries);
    }

    @Test
    @DisplayName("급행 끄기 여정: 노선형 급행 route 를 OTP 탐색에서 빼고 묻는다(본 검색과 같은 제외 목록)")
    void excludeExpressSendsRouteExclusions() {
        RemainingRouteSearchService service = service(otp(List.of(
                rail(watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL, 600))));

        service.search("1:S1", WP, 2000, null, true);
        assertThat(lastQuery.excludedRouteIds())
                .isNotEmpty()
                .containsExactlyInAnyOrderElementsOf(
                        watoo.grd.nextroute.application.subway.service.SubwayExpressRouteFixtures
                                .railServiceClassifier().expressRouteIds());

        service.search("1:S1", WP, 2000, null, false);
        assertThat(lastQuery.excludedRouteIds()).isEmpty();
    }

    @Test
    @DisplayName("급행 끄기 여정: 급행 경로를 자르기 전에 뺀다 — 앞 칸을 급행이 채워도 일반 경로가 상한 안에 남는다")
    void excludeExpressFiltersBeforeTruncation() {
        List<RoutingItinerary> engine = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            engine.add(rail(watoo.grd.nextroute.application.subway.dto.TrainKind.EXPRESS, 600 + 60 * i));
        }
        engine.add(rail(watoo.grd.nextroute.application.subway.dto.TrainKind.DIRECT, 900));
        engine.add(rail(watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL, 960));
        engine.add(rail(watoo.grd.nextroute.application.subway.dto.TrainKind.NORMAL, 1020));

        List<PathResult> off = service(otp(engine)).search("1:S1", WP, 2000, null, true);
        assertThat(off).extracting(p -> p.subPaths().get(0).trainKind()).containsExactly("NORMAL", "NORMAL");
        assertThat(off).extracting(p -> p.subPaths().get(0).sectionTime()).containsExactly(16, 17);

        // 급행 포함 여정은 옛 동작 그대로 — DIRECT 만 빠지고 엔진 순서 앞 5개(전부 급행).
        List<PathResult> on = service(otp(engine)).search("1:S1", WP, 2000, null, false);
        assertThat(on).hasSize(5).extracting(p -> p.subPaths().get(0).trainKind()).containsOnly("EXPRESS");
    }

    @Test
    @DisplayName("급행 끄기 여정인데 급행 경로뿐이면 필터를 푼다(D10) — 다시 묻지 않는다(첫 질의가 이미 노선형 급행 제외)")
    void excludeExpressRelaxesWhenOnlyExpressRemains() {
        RemainingRouteSearchService service = service(otp(List.of(
                rail(watoo.grd.nextroute.application.subway.dto.TrainKind.DIRECT, 500),
                rail(watoo.grd.nextroute.application.subway.dto.TrainKind.EXPRESS, 600),
                rail(watoo.grd.nextroute.application.subway.dto.TrainKind.EXPRESS, 660))));

        List<PathResult> paths = service.search("1:S1", WP, 2000, null, true);

        // 해제해도 DIRECT 는 언제나 빠진다.
        assertThat(paths).extracting(p -> p.subPaths().get(0).trainKind()).containsExactly("EXPRESS", "EXPRESS");
        assertThat(otpCalls).isEqualTo(1);
    }

    @Test
    @DisplayName("급행 끄기 여정의 진짜 무경로(DIRECT 뿐)는 해제하지 않고 빈 목록")
    void excludeExpressTrueNoRouteStaysEmpty() {
        List<PathResult> paths = service(otp(List.of(
                rail(watoo.grd.nextroute.application.subway.dto.TrainKind.DIRECT, 500))))
                .search("1:S1", WP, 2000, null, true);

        assertThat(paths).isEmpty();
    }

    @Test
    @DisplayName("OTP 실패 → 빈 목록(재탐색 계약), 예외 안 던짐")
    void otpFailureEmpty() {
        List<PathResult> paths = service(
                RoutingFailure.of(RoutingFailure.Kind.NO_ROUTE, "NO", "없음"))
                .search("1:S1", WP, 2000);
        assertThat(paths).isEmpty();
    }
}
