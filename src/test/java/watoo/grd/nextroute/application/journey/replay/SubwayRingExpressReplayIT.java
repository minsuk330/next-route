package watoo.grd.nextroute.application.journey.replay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.ManageLiveActivityTokenUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushRelayService;
import watoo.grd.nextroute.application.journey.service.SubwayRingUpdateService;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionTxService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo;
import watoo.grd.nextroute.application.subway.dto.SubwayPositionFetchResult;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.service.RealtimeSubwayCollector;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalDepartureRecorder;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog;
import watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwaySegmentLookup;
import watoo.grd.nextroute.application.subway.service.SubwaySegmentTimeRecorder;
import watoo.grd.nextroute.application.subway.service.SubwayStationAdjacency;
import watoo.grd.nextroute.application.subway.service.SubwayStationIdLookup;
import watoo.grd.nextroute.application.subway.service.SubwayTrainTransitionTracker;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.SeoulSubwayApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.SubwayApiResponse;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.dto.SubwayPositionResponse;
import watoo.grd.nextroute.testsupport.MutableClock;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 급행 추적 녹화 재생(subway-express PR-S4). 2026-10-21 21:37~22:52 KST 운영 실시간을 {@code tools/subway-replay/record.py} 로
 * 녹화한 9호선 급행 9712·일반 9226 조각을 1초 단위로 다시 흘린다. 응답 해석·수집기 행 변환·위치 추적·링 갱신은 운영 코드
 * 그대로이고, 노선 패턴(완행 경로·구간 GTFS 시간)만 운영 OTP fixture(2026-10-07, {@code otp/subway-patterns/})로 꽂는다 —
 * 시험 환경은 OTP 적재를 기대할 수 없다.
 *
 * <p>바인딩은 <b>종류 없이</b> 만든다(배포 전 행과 같은 모양). 링 갱신이 실시간 근거로 한 번 분류해 V72 칸을 채우는지도 함께 본다.
 *
 * <p>단언: 남은 역·다음 역은 그 열차가 서는 역만(D9), 지나치는 역에서 하차역 PASSED 오판 없음, 같은 구간 되감김 없음, 하차 예정
 * 오차가 명시한 범위 안. 숫자는 표준 출력({@code [express-replay]})에 남는다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.subway.ring.push.enabled=true",
        // 2호선 재생({@link SubwayRingReplayIT})과 컨텍스트를 나눈다 — 급행 카탈로그는 첫 사용 때 역 마스터를 잇는다
        "subway.express.replay-context=line9"
})
@DisplayName("지하철 링 재생 — 9호선 급행·일반 녹화")
class SubwayRingExpressReplayIT {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter RECEIVED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SESSION_ID = "sess-subway-ring-express-replay";
    private static final String PATH_KEY = "0";
    private static final Duration RING_TICK = Duration.ofSeconds(5);
    static final String EXPRESS_FIXTURE = "replay/line9-9712-express-gayang-goter";
    static final String NORMAL_FIXTURE = "replay/line9-9226-normal-gayang-dangsan";

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock replayClock() {
            return new MutableClock(Instant.parse("2026-10-21T12:40:00Z"), SEOUL);
        }
    }

    @Autowired MutableClock clock;
    @Autowired ObjectMapper objectMapper;
    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayTrainSelectionTxService selectionTxService;
    @Autowired JourneyLegRepository legRepository;
    @Autowired SubwayRingUpdateService ringService;
    @Autowired SubwayRingPushRelayService pushRelay;
    @Autowired SubwayRealtimeCachePort realtimeCache;
    @Autowired JourneyQueryUseCase journeyQuery;
    @Autowired ManageLiveActivityTokenUseCase liveActivityTokens;
    @Autowired SubwayTrainTransitionTracker tracker;
    @Autowired SubwayStationAdjacency adjacency;
    @Autowired SubwaySegmentLookup segmentLookup;
    @SpyBean SubwayLineOrder lineOrder;
    @Autowired SubwaySegmentTimeRecorder segmentTimeRecorder;
    @Autowired SubwayArrivalDepartureRecorder departureRecorder;
    @Autowired SubwayStationIdLookup stationIdLookup;
    @Autowired SubwayExpressCatalog catalog;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean LiveActivityPushPort pushPort;

    private final List<Long> seededSegmentIds = new ArrayList<>();
    private final List<Long> seededStationIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;

    record Station(String id, String name) {
    }

    record Frame(Instant at, String kind, JsonNode body) {
    }

    /** 링 한 초. */
    record Shown(Instant at, String next, Integer remaining, double value, String quality, Instant alightingAt,
                 String trainKind, String trainLabel) {
    }

    /** 재생 결과. {@code sourceArrivals} = 위치 API 가 우리 열차의 그 역 도착(1)을 처음 말한 원천 시각. */
    record Replay(List<Station> planned, List<Shown> timeline, Map<String, Instant> sourceArrivals,
                  String boundKind, String boundService) {
    }

    // ── 시험 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9호선 급행 9712 가양→고속터미널: 정차역만 세고, 지나치는 역에서 PASSED 로 뛰지 않고, 하차 예정 오차가 범위 안")
    void express9712() throws Exception {
        Replay replay = replay(EXPRESS_FIXTURE);
        Set<String> stops = replay.planned().stream().map(Station::name).collect(Collectors.toSet());
        Station alight = replay.planned().get(replay.planned().size() - 1);

        assertThat(replay.boundKind()).as("배포 전 모양 바인딩을 링이 한 번 분류해 적는다").isEqualTo("EXPRESS");
        assertThat(replay.boundService()).isEqualTo("1009-express");
        assertThat(replay.timeline()).as("링이 그려진다").isNotEmpty();
        assertThat(replay.timeline()).as("다음 역은 급행이 서는 역뿐(D9)")
                .allMatch(s -> stops.contains(s.next()));
        assertThat(replay.timeline()).as("남은 역은 정차역만 센다 — 가양→고속터미널 6역")
                .allMatch(s -> s.remaining() != null && s.remaining() >= 1 && s.remaining() <= 6);
        assertThat(replay.timeline()).as("라벨").allMatch(s -> "EXPRESS".equals(s.trainKind())
                && "급행".equals(s.trainLabel()));
        assertNonIncreasingRemaining(replay.timeline());
        assertNoRewind(replay.timeline());
        assertNoEarlyAlighting(replay, alight);
        assertAlightingError(replay, alight, 240, 120);
    }

    @Test
    @DisplayName("9호선 일반 9226 가양→당산: 모든 역을 세고(6역), 일반 동작과 같다 — 같은 녹화의 대조군")
    void normal9226() throws Exception {
        Replay replay = replay(NORMAL_FIXTURE);
        Station alight = replay.planned().get(replay.planned().size() - 1);

        assertThat(replay.boundKind()).isEqualTo("NORMAL");
        assertThat(replay.boundService()).isNull();
        assertThat(replay.timeline()).isNotEmpty();
        assertThat(replay.timeline()).allMatch(s -> s.remaining() != null && s.remaining() >= 1 && s.remaining() <= 6);
        assertThat(replay.timeline()).as("라벨").allMatch(s -> "일반".equals(s.trainLabel()));
        assertThat(replay.timeline().stream().map(Shown::next).distinct().toList())
                .as("모든 역을 차례로 지난다").containsSubsequence("증미", "등촌", "염창", "신목동", "선유도", "당산");
        assertNonIncreasingRemaining(replay.timeline());
        assertNoRewind(replay.timeline());
        assertNoEarlyAlighting(replay, alight);
        assertAlightingError(replay, alight, 240, 120);
    }

    // ── 단언 ─────────────────────────────────────────────────────────────────

    private static void assertNonIncreasingRemaining(List<Shown> timeline) {
        for (int i = 1; i < timeline.size(); i++) {
            assertThat(timeline.get(i).remaining()).as("남은 역이 다시 늘지 않는다 @%s", timeline.get(i).at())
                    .isLessThanOrEqualTo(timeline.get(i - 1).remaining());
        }
    }

    private static void assertNoRewind(List<Shown> timeline) {
        for (int i = 1; i < timeline.size(); i++) {
            Shown a = timeline.get(i - 1);
            Shown b = timeline.get(i);
            if (a.next().equals(b.next())) {
                assertThat(b.value()).as("되감김 %s→%s @%s", a.value(), b.value(), b.at())
                        .isGreaterThanOrEqualTo(a.value() - 1e-4);
            }
        }
    }

    /** 하차역 도착(1.00)을 원천 도착보다 30초 넘게 먼저 보이지 않는다 — 지나치는 역 관측을 구간 밖(PASSED)으로 보면 여기서 걸린다. */
    private static void assertNoEarlyAlighting(Replay replay, Station alight) {
        Instant source = replay.sourceArrivals().get(alight.id());
        assertThat(source).as("녹화에 하차역 도착이 있다").isNotNull();
        Shown first = replay.timeline().stream()
                .filter(s -> s.next().equals(alight.name()) && s.value() >= 0.999)
                .findFirst().orElse(null);
        assertThat(first).as("하차역 도착을 링이 보인다").isNotNull();
        System.out.printf("[express-replay] %s 원천 도착 %s → 링 1.00 %s (%+ds)%n", alight.name(), source, first.at(),
                Duration.between(source, first.at()).toSeconds());
        assertThat(first.at()).as("하차역 PASSED 오판 없음").isAfterOrEqualTo(source.minusSeconds(30));
    }

    /**
     * 하차 예정 오차 = 링의 하차 예정 − 원천 하차역 도착. 승차역을 떠난 뒤부터 도착 전까지 매초 잰다.
     *
     * @param meanBound  평균 절대 오차 상한(초)
     * @param finalBound 마지막 구간(다음 역 = 하차역)의 평균 절대 오차 상한(초)
     */
    private static void assertAlightingError(Replay replay, Station alight, long meanBound, long finalBound) {
        Instant actual = replay.sourceArrivals().get(alight.id());
        List<Long> errors = new ArrayList<>();
        List<Long> finals = new ArrayList<>();
        String first = replay.planned().get(1).name();
        boolean left = false;
        for (Shown s : replay.timeline()) {
            left |= !s.next().equals(replay.planned().get(0).name());
            if (!left || s.at().isAfter(actual) || s.alightingAt() == null) {
                continue;
            }
            long error = Duration.between(actual, s.alightingAt()).toSeconds();
            errors.add(error);
            if (s.next().equals(alight.name())) {
                finals.add(error);
            }
        }
        assertThat(errors).as("하차 예정을 잰다(첫 구간 %s 부터)", first).isNotEmpty();
        double mean = errors.stream().mapToLong(Math::abs).average().orElseThrow();
        long max = errors.stream().mapToLong(Math::abs).max().orElseThrow();
        double finalMean = finals.stream().mapToLong(Math::abs).average().orElse(0);
        double bias = errors.stream().mapToLong(Long::longValue).average().orElseThrow();
        System.out.printf("[express-replay] 하차 예정 오차 n=%d 평균|e|=%.0fs 치우침=%+.0fs 최대|e|=%ds 마지막 구간 평균|e|=%.0fs%n",
                errors.size(), mean, bias, max, finalMean);
        assertThat(mean).as("하차 예정 평균 절대 오차(초)").isLessThanOrEqualTo(meanBound);
        assertThat(finalMean).as("마지막 구간 하차 예정 평균 절대 오차(초)").isLessThanOrEqualTo(finalBound);
    }

    // ── 재생 ─────────────────────────────────────────────────────────────────

    private Replay replay(String fixture) throws Exception {
        Properties props = props(fixture);
        String line = props.getProperty("REPLAY_LINE");
        String trainNo = props.getProperty("REPLAY_TRAIN");
        List<Station> planned = stations(props.getProperty("REPLAY_STATIONS"));
        List<Station> allStops = stations(props.getProperty("REPLAY_ALL_STOPS"));
        Instant from = Instant.parse(props.getProperty("REPLAY_FROM"));
        Instant to = Instant.parse(props.getProperty("REPLAY_TO"));
        Instant boardAt = Instant.parse(props.getProperty("REPLAY_BOARD_AT"));
        clock.set(from);

        clearRedis();
        delegatePatternsToOtpFixture();
        seed(line, allStops);
        adjacency.reload();
        awaitCatalog();
        startJourney(props, planned);
        liveActivityOn();

        RealtimeSubwayCollector collector = collector();
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate,
                Mockito.mock(watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker.class,
                        Mockito.RETURNS_DEFAULTS),
                "k", "k", "http://replay", "k", "k", "http://replay");
        List<Frame> frames = loadFrames(fixture, props.getProperty("REPLAY_LINE_NAME"), from, to);
        Set<String> chainIds = allStops.stream().map(Station::id).collect(Collectors.toSet());
        Map<String, Instant> sourceArrivals = new LinkedHashMap<>();

        List<Shown> timeline = new ArrayList<>();
        boolean selected = false;
        int frameIndex = 0;
        Instant nextRingTick = from;
        for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(1)) {
            clock.set(t);
            while (frameIndex < frames.size() && !frames.get(frameIndex).at().isAfter(t)) {
                Frame f = frames.get(frameIndex++);
                if (f.kind().equals("arrival")) {
                    applyArrival(collector, adapter, restTemplate, f);
                } else {
                    applyPosition(adapter, restTemplate, f, line, trainNo, chainIds, sourceArrivals);
                }
            }
            if (!selected && !t.isBefore(boardAt)) {
                selectAboard(trainNo, line);
                selected = true;
            }
            if (!t.isBefore(nextRingTick)) {
                ringService.runOnce();
                pushRelay.runOnce();
                nextRingTick = t.plus(RING_TICK);
            }
            SubwayGuidance g = journeyQuery.get(userId, journeyId).orElseThrow().subwayGuidance();
            if (selected && g != null && g.segmentProgress() != null) {
                timeline.add(new Shown(t, g.nextStationName(), g.remainingStations(), shown(g, t),
                        g.quality().name(), g.estimatedAlightingAt(), g.trainKind(), g.trainLabel()));
            }
        }
        Map<String, Object> bound = jdbcTemplate.queryForMap("""
                select train_kind, train_service_id from subway_leg_binding where journey_id = ?
                """, journeyId);
        String route = timeline.stream().map(Shown::next).distinct().collect(Collectors.joining(" > "));
        System.out.printf("[express-replay] %s 열차 %s 종류 %s 서비스 %s 링 %d초, 다음 역 %s%n", fixture, trainNo,
                bound.get("train_kind"), bound.get("train_service_id"), timeline.size(), route);
        return new Replay(planned, timeline, sourceArrivals, (String) bound.get("train_kind"),
                (String) bound.get("train_service_id"));
    }

    /**
     * 노선 패턴은 운영 OTP fixture 로 계산한다 — 완행 경로·구간 GTFS 시간·급행 정확 일치 모두 운영 코드
     * ({@link SubwayExpressFixtures#fixtureLineOrder}). 진짜 빈 위에 덮어 OTP 지연 적재가 돌지 않게 한다.
     */
    private void delegatePatternsToOtpFixture() {
        SubwayLineOrder fixture = SubwayExpressFixtures.fixtureLineOrder();
        Mockito.doAnswer(inv -> fixture.allStopPath(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                inv.getArgument(3))).when(lineOrder).allStopPath(any(), any(), any(), any());
        Mockito.doAnswer(inv -> fixture.segmentTimes(inv.<String>getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3))).when(lineOrder).segmentTimes(any(), any(), any(), any());
        Mockito.doAnswer(inv -> fixture.segmentTimes(inv.<String>getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)))
                .when(lineOrder).segmentTimes(any(), any(), any(), any(), any());
        Mockito.doAnswer(inv -> fixture.arrivalGapSeconds(inv.<String>getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3))).when(lineOrder).arrivalGapSeconds(any(), any(), any(), any());
        Mockito.doAnswer(inv -> fixture.arrivalGapSeconds(inv.<String>getArgument(0), inv.getArgument(1),
                inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)))
                .when(lineOrder).arrivalGapSeconds(any(), any(), any(), any(), any());
        Mockito.doAnswer(inv -> fixture.behind(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                inv.<Integer>getArgument(3))).when(lineOrder).behind(any(), any(), any(), anyInt());
        Mockito.doAnswer(inv -> fixture.prevOf(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
                .when(lineOrder).prevOf(any(), any(), any());
        Mockito.doAnswer(inv -> fixture.nextOf(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)))
                .when(lineOrder).nextOf(any(), any(), any());
    }

    /** 카탈로그가 9호선 역을 이을 때까지(보류면 30초 재시도) 기다린다 — 첫 사용 전에 역 마스터가 있어야 한다. */
    private void awaitCatalog() throws InterruptedException {
        for (int i = 0; i < 40 && catalog.service("1009-express").isEmpty(); i++) {
            Thread.sleep(1000);
        }
        assertThat(catalog.service("1009-express")).as("급행 카탈로그 9호선 서비스").isPresent();
    }

    private RealtimeSubwayCollector collector() {
        // 수집기는 worker 전용 빈이다. 행 변환(toTrain)만 쓰므로 같은 부품으로 직접 만든다(SubwayRingReplayIT 와 같다)
        return new RealtimeSubwayCollector(null, realtimeCache, segmentLookup, stationIdLookup, null,
                segmentTimeRecorder, departureRecorder,
                new watoo.grd.nextroute.application.subway.observability.SubwayRealtimeMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()),
                new watoo.grd.nextroute.application.journey.reeval.SubwayChangeFeed(
                        new watoo.grd.nextroute.application.journey.reeval.ReevaluationProperties(),
                        null, null, null, null, null),
                new watoo.grd.nextroute.common.lock.LeaderLease() {
                    public boolean isLeader() { return true; }
                    public long epoch() { return 0; }
                });
    }

    private void applyArrival(RealtimeSubwayCollector collector, SeoulSubwayApiAdapter adapter,
                              RestTemplate restTemplate, Frame f) throws IOException {
        SubwayApiResponse response = objectMapper.treeToValue(f.body(), SubwayApiResponse.class);
        given(restTemplate.getForObject(any(URI.class), eq(SubwayApiResponse.class))).willReturn(response);
        List<SubwayArrivalInfo> arrivals = adapter.getRealtimeArrival();
        if (arrivals.isEmpty()) {
            return;
        }
        stationIdLookup.update(arrivals);
        List<SubwayRealtimeTrain> trains = arrivals.stream()
                .map(info -> (SubwayRealtimeTrain) ReflectionTestUtils.invokeMethod(collector, "toTrain", info))
                .toList();
        realtimeCache.saveSnapshot(SubwayRealtimeSnapshot.builder()
                .collectedAt(f.at().atZone(SEOUL).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .trains(trains)
                .build(), 3600);
        segmentTimeRecorder.record(trains);
        departureRecorder.record(trains, f.at());
    }

    private void applyPosition(SeoulSubwayApiAdapter adapter, RestTemplate restTemplate, Frame f, String line,
                               String trainNo, Set<String> chainIds, Map<String, Instant> sourceArrivals)
            throws IOException {
        SubwayPositionResponse response = objectMapper.treeToValue(f.body(), SubwayPositionResponse.class);
        given(restTemplate.getForObject(any(URI.class), eq(SubwayPositionResponse.class))).willReturn(response);
        SubwayPositionFetchResult fetched = adapter.getRealtimePosition(line);
        if (!fetched.isComplete()) {
            return;
        }
        tracker.observe(new SubwayPositionFetchResult(fetched.lineId(), fetched.rows(), fetched.totalCount(),
                fetched.status(), f.at(), fetched.errorCode()));
        fetched.rows().stream()
                .filter(r -> trainNo.equals(r.trainNo()) && chainIds.contains(r.stationId())
                        && r.status() == SubwayTrainStatus.ARRIVED && r.receivedAt() != null)
                .forEach(r -> sourceArrivals.putIfAbsent(r.stationId(),
                        LocalDateTime.parse(r.receivedAt(), RECEIVED_AT).atZone(SEOUL).toInstant()));
    }

    private List<Frame> loadFrames(String fixture, String lineName, Instant from, Instant to) throws IOException {
        List<Frame> frames = new ArrayList<>();
        read(fixture + "/arrival.jsonl", "arrival", frames, from, to);
        read(fixture + "/position-" + lineName + ".jsonl", "position", frames, from, to);
        frames.sort(Comparator.comparing(Frame::at));
        return frames;
    }

    private void read(String resource, String kind, List<Frame> frames, Instant from, Instant to) throws IOException {
        try (var in = getClass().getClassLoader().getResourceAsStream(resource);
             BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String l;
            while ((l = reader.readLine()) != null) {
                JsonNode node = objectMapper.readTree(l);
                Instant at = Instant.parse(node.get("fetchedAt").asText());
                if (at.isBefore(from.minusSeconds(600)) || at.isAfter(to) || node.get("body").has("error")) {
                    continue;
                }
                frames.add(new Frame(at, kind, node.get("body")));
            }
        }
    }

    /** 앱 {@code shownSubwayProgress} 와 같은 식. */
    private static double shown(SubwayGuidance g, Instant at) {
        double base = g.segmentProgress().doubleValue();
        double cap = g.progressTarget() == null ? 0.9 : g.progressTarget().doubleValue();
        Instant baseAt = g.progressBaseAt();
        Instant eta = g.estimatedNextStationAt();
        if (g.quality() == null || !"NORMAL".equals(g.quality().name()) || baseAt == null || eta == null
                || !eta.isAfter(baseAt) || cap <= base) {
            return base;
        }
        Instant clamped = at.isBefore(baseAt) ? baseAt : at.isAfter(eta) ? eta : at;
        double ratio = (double) Duration.between(baseAt, clamped).toMillis() / Duration.between(baseAt, eta).toMillis();
        return base + (cap - base) * ratio;
    }

    // ── 무대 ─────────────────────────────────────────────────────────────────

    /** 인접 표(이름)와, 역 마스터에 없을 때만 역 행을 넣는다(로컬 DB 는 개발 역 마스터를 함께 쓴다). */
    private void seed(String line, List<Station> allStops) {
        for (int i = 1; i < allStops.size(); i++) {
            seededSegmentIds.add(jdbcTemplate.queryForObject("""
                    insert into subway_segment
                        (created_at, updated_at, line_id, depart_station_id, arrive_station_id, distance, seq, travel_time)
                    values (now(), now(), ?, ?, ?, 1.0, ?, 120) returning id
                    """, Long.class, line, allStops.get(i - 1).name(), allStops.get(i).name(), i));
        }
        for (var station : SubwayExpressFixtures.stations(line)) {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from subway_station where line_id = ? and statn_id = ?", Integer.class, line,
                    station.getStationId());
            if (exists == null || exists == 0) {
                seededStationIds.add(jdbcTemplate.queryForObject("""
                        insert into subway_station (created_at, updated_at, line_id, statn_id, statn_nm)
                        values (now(), now(), ?, ?, ?) returning id
                        """, Long.class, line, station.getStationId(), station.getStationName()));
            }
        }
    }

    private void startJourney(Properties props, List<Station> stations) {
        List<StationResult> results = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            results.add(new StationResult(i, String.valueOf(900000 + i), stations.get(i).name(), null, null));
        }
        Station first = stations.get(0);
        Station last = stations.get(stations.size() - 1);
        PathResult path = new PathResult(1, null, List.of(
                SubPathFixture.subway(stations.size() * 2).names(first.name(), last.name())
                        .subwayStations(900000, 900000 + stations.size() - 1)
                        .wayCode(Integer.parseInt(props.getProperty("REPLAY_WAY_CODE")))
                        .lanes(SubPathFixture.subwayLane(props.getProperty("REPLAY_LINE_NAME"),
                                Integer.parseInt(props.getProperty("REPLAY_LANE_CODE"))))
                        .stations(results.toArray(StationResult[]::new))
                        .build(),
                SubPathFixture.walk(3).names(last.name(), "목적지").distance(200.0).build()), List.of());
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, path,
                        Instant.parse(props.getProperty("REPLAY_TO")).plusSeconds(3600))));
        userId = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation) values (now(), now(), 0) returning id
                """, Long.class);
        JourneySnapshot journey = startJourneyUseCase.start(userId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = ?
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                """, first.id(), journeyId);
    }

    /** 종류 없이 묶는다 — 배포 전 바인딩과 같은 모양. 링이 실시간으로 분류한다. */
    private void selectAboard(String trainNo, String line) {
        JourneyLeg ride = legRepository.findAll().stream()
                .filter(l -> journeyId.equals(l.getJourneyId()) && l.getType().name().equals("RIDE_SUBWAY"))
                .findFirst().orElseThrow();
        String direction = realtimeCache.readSnapshot().orElseThrow().getTrains().stream()
                .filter(tr -> trainNo.equals(tr.getTrainNo()))
                .map(SubwayRealtimeTrain::getDirection)
                .findFirst().orElseThrow(() -> new IllegalStateException("녹화에 열차 " + trainNo + " 도착 행이 없다"));
        selectionTxService.bind(ride, new SubwayTrainSelectionTxService.ChosenTrain(trainNo,
                LocalDate.ofInstant(clock.instant(), SEOUL), line, direction, true));
    }

    private void liveActivityOn() {
        jdbcTemplate.update("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment, enabled, last_seen_at)
                values (now(), now(), ?, 'IOS', 'FCM', 'device-token', 'PRODUCTION', true, now())
                """, userId);
        liveActivityTokens.register(userId, journeyId, "activity-token", "device-token", PushEnvironment.PRODUCTION);
        given(pushPort.send(any(), any(), any(), anyLong(), any()))
                .willReturn(new LiveActivityPushPort.PushResult(true, null));
    }

    private void clearRedis() {
        var keys = redisTemplate.keys("*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @AfterEach
    void cleanUp() {
        if (journeyId != null) {
            for (String table : List.of("subway_ring_push_outbox", "subway_live_activity_token",
                    "subway_binding_event", "subway_leg_binding")) {
                jdbcTemplate.update("delete from " + table + " where journey_id = ?", journeyId);
            }
        }
        if (userId != null) {
            jdbcTemplate.update("delete from user_push_token where user_id = ?", userId);
            jdbcTemplate.update("""
                    update journey_leg set selected_route_option_id = null
                    where journey_id in (select id from journey_session where user_id = ?)
                    """, userId);
            jdbcTemplate.update("""
                    delete from journey_leg_route_option where journey_leg_id in (
                        select l.id from journey_leg l join journey_session s on s.id = l.journey_id where s.user_id = ?)
                    """, userId);
            for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event")) {
                jdbcTemplate.update("delete from " + table
                        + " where journey_id in (select id from journey_session where user_id = ?)", userId);
            }
            jdbcTemplate.update("delete from journey_session where user_id = ?", userId);
            jdbcTemplate.update("delete from users where id = ?", userId);
        }
        seededSegmentIds.forEach(id -> jdbcTemplate.update("delete from subway_segment where id = ?", id));
        seededStationIds.forEach(id -> jdbcTemplate.update("delete from subway_station where id = ?", id));
        seededSegmentIds.clear();
        seededStationIds.clear();
        journeyId = null;
        userId = null;
        Mockito.reset(lineOrder);
    }

    private static List<Station> stations(String spec) {
        return Arrays.stream(spec.split(",")).map(s -> s.split(":", 2))
                .map(p -> new Station(p[0].trim(), p[1].trim())).toList();
    }

    private static Properties props(String fixture) throws IOException {
        Properties props = new Properties();
        try (var in = SubwayRingExpressReplayIT.class.getClassLoader().getResourceAsStream(fixture + "/replay.properties")) {
            props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return props;
    }

    @SuppressWarnings("unused")
    private static String str(Object o) {
        return o == null ? "" : o instanceof BigDecimal b ? b.toPlainString() : o.toString();
    }
}
