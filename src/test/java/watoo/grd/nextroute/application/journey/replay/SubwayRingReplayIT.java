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
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.service.RealtimeSubwayCollector;
import watoo.grd.nextroute.application.subway.service.SubwayArrivalDepartureRecorder;
import watoo.grd.nextroute.application.subway.service.SubwaySegmentTimeRecorder;
import watoo.grd.nextroute.application.subway.service.SubwayLineOrder;
import watoo.grd.nextroute.application.subway.service.SubwaySegmentLookup;
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
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 녹화한 서울 지하철 실시간 응답을 시간 순서대로 다시 흘려 <b>앱 게이지가 어떻게 그려지는지</b>를 1초 단위로 기록한다.
 *
 * <p>환경 변수가 없으면 저장소의 녹화 조각({@link #FIXTURE})으로 돌고 <b>회귀 단언</b>을 건다 — 회색 STALE 없음, 같은 구간에서
 * 게이지가 뒤로 가지 않음, 하차 예정이 늘 있음, 역당 push 6건 이하. {@code REPLAY_DIR} 를 주면 그 녹화로 돌고 CSV 만 남긴다
 * (단언 없음). 녹화는 {@code tools/subway-replay/record.py}, 열차 고르기 {@code pick.py}, 그림 {@code render.py}.
 *
 * <p>응답 해석은 운영 코드를 그대로 탄다: 원본 JSON → {@link SeoulSubwayApiAdapter}(RestTemplate 만 가짜) →
 * 수집기의 행 변환·구간 실측·출발 기록 → 도착 스냅샷, 위치는 추적기. 시계만 녹화 시각으로 돌린다. 링 갱신은 운영과 같은
 * 5초, 발송 relay 는 매 tick.
 *
 * <p>입력(환경 변수): {@code REPLAY_DIR}(녹화 폴더), {@code REPLAY_LINE}(1002), {@code REPLAY_LINE_NAME}(2호선),
 * {@code REPLAY_LANE_CODE}(2), {@code REPLAY_WAY_CODE}, {@code REPLAY_TRAIN}(열차번호),
 * {@code REPLAY_STATIONS}(승차역~하차역 {@code statnId:역명} 쉼표 목록), {@code REPLAY_BOARD_AT}(탑승 선택 시각 ISO),
 * {@code REPLAY_FROM}/{@code REPLAY_TO}(재생 구간 ISO), {@code REPLAY_TYPICAL}(GTFS 구간 시간), {@code REPLAY_OUT}(CSV 경로).
 * 조각은 같은 이름을 {@code replay.properties} 에 둔다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.subway.ring.push.enabled=true"
})
@DisplayName("지하철 링 재생 — 녹화한 실시간 응답으로 게이지 시계열 만들기")
class SubwayRingReplayIT {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 기본 녹화 조각(test resources). */
    static final String FIXTURE = "replay/line2-7399-seocho-seolleung";
    private static final boolean FROM_ENV = System.getenv("REPLAY_DIR") != null && !System.getenv("REPLAY_DIR").isBlank();
    private static final java.util.Properties FIXTURE_PROPS = loadFixtureProps();
    private static final String SESSION_ID = "sess-subway-ring-replay";
    private static final String PATH_KEY = "0";
    private static final Duration RING_TICK = Duration.ofSeconds(5);

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
            return new MutableClock(Instant.parse(env("REPLAY_FROM")), SEOUL);
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
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean LiveActivityPushPort pushPort;

    private final List<Long> seededSegmentIds = new ArrayList<>();
    private final List<Long> seededStationIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;
    private RealtimeSubwayCollector collector;

    record Station(String id, String name) {
    }

    /** 녹화 한 줄. {@code kind} = arrival | position. */
    record Frame(Instant at, String kind, JsonNode body) {
    }

    @Test
    void replay() throws Exception {
        String line = env("REPLAY_LINE");
        String trainNo = env("REPLAY_TRAIN");
        List<Station> stations = Arrays.stream(env("REPLAY_STATIONS").split(","))
                .map(s -> s.split(":", 2)).map(p -> new Station(p[0].trim(), p[1].trim())).toList();
        Instant from = Instant.parse(env("REPLAY_FROM"));
        Instant to = Instant.parse(env("REPLAY_TO"));
        Instant boardAt = Instant.parse(env("REPLAY_BOARD_AT"));

        clearRedis();
        stubTypical(stations);
        seed(line, stations);
        adjacency.reload();
        startJourney(stations);
        liveActivityOn();

        // 수집기는 worker 전용 빈이다. 행 변환(toTrain)만 쓰므로 같은 부품으로 직접 만든다.
        collector = new RealtimeSubwayCollector(null, realtimeCache, segmentLookup, stationIdLookup, null,
                segmentTimeRecorder, departureRecorder);
        RestTemplate restTemplate = Mockito.mock(RestTemplate.class);
        SeoulSubwayApiAdapter adapter = new SeoulSubwayApiAdapter(restTemplate,
                Mockito.mock(watoo.grd.nextroute.infrastructure.adapter.out.api.ApiCallBlocker.class, Mockito.RETURNS_DEFAULTS),
                "k", "k", "http://replay", "k", "k", "http://replay");

        List<Frame> frames = loadFrames(replayDir(), env("REPLAY_LINE_NAME"), from, to);
        Path out = FROM_ENV ? Path.of(env("REPLAY_OUT")) : Path.of("build", "replay", "line2-7399.csv");
        Files.createDirectories(out.getParent());

        boolean selected = false;
        int frameIndex = 0;
        Instant nextRingTick = from;
        int pushesBefore = 0;
        try (PrintWriter csv = new PrintWriter(Files.newBufferedWriter(out))) {
            csv.println("t,shown,segmentProgress,progressTarget,progressBaseAt,eta,validUntil,quality,"
                    + "nextStation,remaining,holdReason,phase,alightingAt,bindingStatus,pushes,ourArrival,ourPosition");
            String ourArrival = "";
            String ourPosition = "";
            for (Instant t = from; !t.isAfter(to); t = t.plusSeconds(1)) {
                clock.set(t);
                while (frameIndex < frames.size() && !frames.get(frameIndex).at().isAfter(t)) {
                    Frame f = frames.get(frameIndex++);
                    if (f.kind().equals("arrival")) {
                        ourArrival = applyArrival(adapter, restTemplate, f, trainNo, stations);
                    } else {
                        ourPosition = applyPosition(adapter, restTemplate, f, line, trainNo);
                    }
                }
                if (!selected && !t.isBefore(boardAt)) {
                    selectAboard(trainNo, line, stations);
                    selected = true;
                }
                if (!t.isBefore(nextRingTick)) {
                    ringService.runOnce();
                    pushRelay.runOnce();
                    nextRingTick = t.plus(RING_TICK);
                }
                int pushes = Mockito.mockingDetails(pushPort).getInvocations().size();
                SubwayGuidance g = journeyQuery.get(userId, journeyId).orElseThrow().subwayGuidance();
                csv.println(row(t, g, pushes - pushesBefore, ourArrival, ourPosition));
                pushesBefore = pushes;
                if (selected && g != null && g.segmentProgress() != null) {
                    timeline.add(new Shown(t, g.nextStationName(), shown(g, t), g.quality().name(),
                            g.estimatedAlightingAt() != null, pushes));
                }
            }
        }
        if (!FROM_ENV) {
            assertGauge(timeline, stations.size() - 1);
        }
    }

    /** 게이지 한 초. */
    record Shown(Instant at, String next, double value, String quality, boolean alighting, int pushesSoFar) {
    }

    private final List<Shown> timeline = new ArrayList<>();

    /** 녹화 조각 회귀: STALE 없음, 같은 구간 되감김 없음, 하차 예정 늘 있음, 역당 push 6건 이하. */
    private static void assertGauge(List<Shown> timeline, int segments) {
        org.assertj.core.api.Assertions.assertThat(timeline).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(timeline).as("회색 STALE 이 없다")
                .noneMatch(s -> s.quality().equals("STALE"));
        org.assertj.core.api.Assertions.assertThat(timeline).as("하차 예정이 늘 있다")
                .allMatch(Shown::alighting);
        for (int i = 1; i < timeline.size(); i++) {
            Shown a = timeline.get(i - 1);
            Shown b = timeline.get(i);
            if (a.next().equals(b.next())) {
                org.assertj.core.api.Assertions.assertThat(b.value()).as("되감김 %s→%s @%s", a.value(), b.value(), b.at())
                        .isGreaterThanOrEqualTo(a.value() - 1e-4);
            }
        }
        int pushes = timeline.get(timeline.size() - 1).pushesSoFar() - timeline.get(0).pushesSoFar();
        org.assertj.core.api.Assertions.assertThat(pushes).as("역당 push 6건 이하").isLessThanOrEqualTo(6 * segments);
    }

    // ── 응답 흘리기 ──────────────────────────────────────────────────────────

    /** 수집기 한 회차와 같은 일을 한다(벽시계·운행시간 판정만 뺐다). 우리 열차 행 요약을 돌려준다. */
    private String applyArrival(SeoulSubwayApiAdapter adapter, RestTemplate restTemplate, Frame f,
                                String trainNo, List<Station> stations) throws IOException {
        SubwayApiResponse response = objectMapper.treeToValue(f.body(), SubwayApiResponse.class);
        given(restTemplate.getForObject(any(URI.class), eq(SubwayApiResponse.class))).willReturn(response);
        List<SubwayArrivalInfo> arrivals = adapter.getRealtimeArrival();
        if (arrivals.isEmpty()) {
            return "";
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
        List<String> ids = stations.stream().map(Station::id).toList();
        return trains.stream()
                .filter(tr -> trainNo.equals(tr.getTrainNo()) && ids.contains(tr.getStationId()))
                .map(tr -> tr.getStationName() + "/" + tr.getArrivalCode()
                        + (tr.getArrivalSeconds() == null ? "" : "/" + tr.getArrivalSeconds() + "s")
                        + "@" + tr.getReceivedAt())
                .reduce((a, b) -> a + " " + b).orElse("");
    }

    private String applyPosition(SeoulSubwayApiAdapter adapter, RestTemplate restTemplate, Frame f,
                                 String line, String trainNo) throws IOException {
        SubwayPositionResponse response = objectMapper.treeToValue(f.body(), SubwayPositionResponse.class);
        given(restTemplate.getForObject(any(URI.class), eq(SubwayPositionResponse.class))).willReturn(response);
        SubwayPositionFetchResult fetched = adapter.getRealtimePosition(line);
        if (!fetched.isComplete()) {
            return "";
        }
        tracker.observe(new SubwayPositionFetchResult(fetched.lineId(), fetched.rows(), fetched.totalCount(),
                fetched.status(), f.at(), fetched.errorCode()));
        return fetched.rows().stream()
                .filter(r -> trainNo.equals(r.trainNo()))
                .map(r -> r.stationName() + "/" + r.status() + "@" + r.receivedAt())
                .findFirst().orElse("");
    }

    private List<Frame> loadFrames(Path dir, String lineName, Instant from, Instant to) throws IOException {
        List<Frame> frames = new ArrayList<>();
        read(dir.resolve("arrival.jsonl"), "arrival", frames, from, to);
        read(dir.resolve("position-" + lineName + ".jsonl"), "position", frames, from, to);
        frames.sort(Comparator.comparing(Frame::at));
        return frames;
    }

    private void read(Path file, String kind, List<Frame> frames, Instant from, Instant to) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(file)) {
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

    /**
     * 구간 GTFS 전형 시간({@code REPLAY_TYPICAL}: {@code 도착역명:정차초:운행초} 쉼표 목록). 패턴은 운영에서 OTP 로 적재하는데,
     * 시험 환경은 그 적재(호출당 5초 예산)를 기대할 수 없어 값을 받아 꽂는다. 없으면 실제 적재에 맡긴다.
     */
    private void stubTypical(List<Station> stations) {
        String spec = FROM_ENV ? System.getenv("REPLAY_TYPICAL") : FIXTURE_PROPS.getProperty("REPLAY_TYPICAL");
        if (spec == null || spec.isBlank()) {
            return;
        }
        int[] dwell = new int[stations.size()];
        int[] run = new int[stations.size()];
        for (String item : spec.split(",")) {
            String[] p = item.trim().split(":");
            Station to = stations.stream().filter(st -> st.name().equals(p[0])).findFirst().orElseThrow();
            int index = stations.indexOf(to);
            Station from = stations.get(index - 1);
            dwell[index - 1] = Integer.parseInt(p[1]);
            run[index] = Integer.parseInt(p[2]);
            Mockito.doReturn(Optional.of(new SubwayLineOrder.SegmentTimes(Integer.valueOf(p[1]), Integer.parseInt(p[2]))))
                    .when(lineOrder).segmentTimes(any(), any(), eq(from.id()), eq(to.id()));
        }
        // 도착 → 도착 간격 = 사이 역 정차 + 운행 합(하차 예정용).
        for (int i = 0; i < stations.size(); i++) {
            for (int j = i + 1; j < stations.size(); j++) {
                int gap = 0;
                for (int k = i + 1; k <= j; k++) {
                    gap += dwell[k - 1] + run[k];
                }
                Mockito.doReturn(Optional.of(gap)).when(lineOrder)
                        .arrivalGapSeconds(any(), any(), eq(stations.get(i).id()), eq(stations.get(j).id()));
            }
        }
    }

    // ── 출력 ─────────────────────────────────────────────────────────────────

    private String row(Instant t, SubwayGuidance g, int pushes, String ourArrival, String ourPosition) {
        return String.join(",",
                t.toString(),
                g == null ? "" : String.valueOf(shown(g, t)),
                g == null ? "" : str(g.segmentProgress()),
                g == null ? "" : str(g.progressTarget()),
                g == null ? "" : str(g.progressBaseAt()),
                g == null ? "" : str(g.estimatedNextStationAt()),
                g == null ? "" : str(g.progressValidUntil()),
                g == null ? "" : str(g.quality()),
                g == null ? "" : str(g.nextStationName()),
                g == null ? "" : str(g.remainingStations()),
                g == null ? "" : str(g.holdReason()),
                g == null ? "" : str(g.phase()),
                g == null ? "" : str(g.estimatedAlightingAt()),
                g == null ? "" : str(g.bindingStatus()),
                String.valueOf(pushes),
                quote(ourArrival),
                quote(ourPosition));
    }

    /** 앱 {@code shownSubwayProgress} 와 같은 식(NORMAL + 예정 > 앵커일 때만 상한까지 보간). */
    private static double shown(SubwayGuidance g, Instant at) {
        if (g.segmentProgress() == null) {
            return -1;
        }
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

    private static String str(Object o) {
        return o == null ? "" : o instanceof BigDecimal b ? b.toPlainString() : o.toString();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\"", "'") + "\"";
    }

    // ── 무대 ─────────────────────────────────────────────────────────────────

    private void seed(String line, List<Station> stations) {
        for (int i = 1; i < stations.size(); i++) {
            seededSegmentIds.add(jdbcTemplate.queryForObject("""
                    insert into subway_segment
                        (created_at, updated_at, line_id, depart_station_id, arrive_station_id, distance, seq, travel_time)
                    values (now(), now(), ?, ?, ?, 1.0, ?, 120) returning id
                    """, Long.class, line, stations.get(i - 1).name(), stations.get(i).name(), i));
        }
        for (Station s : stations) {
            seededStationIds.add(jdbcTemplate.queryForObject("""
                    insert into subway_station (created_at, updated_at, line_id, statn_id, statn_nm)
                    values (now(), now(), ?, ?, ?) returning id
                    """, Long.class, line, s.id(), s.name()));
        }
    }

    private void startJourney(List<Station> stations) {
        List<StationResult> results = new ArrayList<>();
        for (int i = 0; i < stations.size(); i++) {
            results.add(new StationResult(i, String.valueOf(900000 + i), stations.get(i).name(), null, null));
        }
        Station first = stations.get(0);
        Station last = stations.get(stations.size() - 1);
        PathResult path = new PathResult(1, null, List.of(
                SubPathFixture.subway(stations.size() * 2).names(first.name(), last.name())
                        .subwayStations(900000, 900000 + stations.size() - 1)
                        .wayCode(Integer.parseInt(env("REPLAY_WAY_CODE")))
                        .lanes(SubPathFixture.subwayLane(env("REPLAY_LINE_NAME"), Integer.parseInt(env("REPLAY_LANE_CODE"))))
                        .stations(results.toArray(StationResult[]::new))
                        .build(),
                SubPathFixture.walk(3).names(last.name(), "목적지").distance(200.0).build()), List.of());
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, path, Instant.parse(env("REPLAY_TO")).plusSeconds(3600))));
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

    private void selectAboard(String trainNo, String line, List<Station> stations) {
        JourneyLeg ride = legRepository.findAll().stream()
                .filter(l -> journeyId.equals(l.getJourneyId()) && l.getType().name().equals("RIDE_SUBWAY"))
                .findFirst().orElseThrow();
        String direction = directionOf(trainNo);
        selectionTxService.bind(ride, new SubwayTrainSelectionTxService.ChosenTrain(trainNo,
                LocalDate.ofInstant(clock.instant(), SEOUL), line, direction, true));
    }

    /** 우리 열차의 방향은 녹화된 도착 행에서 읽는다(외선/내선·상행/하행 표기를 운영 변환 그대로). */
    private String directionOf(String trainNo) {
        return realtimeCache.readSnapshot().orElseThrow().getTrains().stream()
                .filter(tr -> trainNo.equals(tr.getTrainNo()))
                .map(SubwayRealtimeTrain::getDirection)
                .findFirst().orElseThrow(() -> new IllegalStateException("녹화에 열차 " + trainNo + " 도착 행이 없다"));
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
    }

    private static Path replayDir() {
        if (FROM_ENV) {
            return Path.of(System.getenv("REPLAY_DIR"));
        }
        try {
            return Path.of(SubwayRingReplayIT.class.getClassLoader().getResource(FIXTURE).toURI());
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Properties loadFixtureProps() {
        java.util.Properties props = new java.util.Properties();
        try (var in = SubwayRingReplayIT.class.getClassLoader().getResourceAsStream(FIXTURE + "/replay.properties")) {
            if (in != null) {
                props.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return props;
    }

    private static String env(String name) {
        String v = FROM_ENV ? System.getenv(name) : FIXTURE_PROPS.getProperty(name);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("환경 변수 " + name + " 가 필요하다");
        }
        return v;
    }
}
