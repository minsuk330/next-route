package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import com.fasterxml.jackson.databind.JsonNode;
import org.mockito.ArgumentCaptor;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionTxService;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.port.in.JourneyQueryUseCase;
import watoo.grd.nextroute.application.journey.port.in.ManageLiveActivityTokenUseCase;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushRelayService;
import watoo.grd.nextroute.application.journey.service.SubwayRingUpdateService;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.dto.SubwayTrainStatus;
import watoo.grd.nextroute.application.subway.service.SubwayStationAdjacency;
import watoo.grd.nextroute.application.subway.service.SubwayTrainTransitionTracker;
import watoo.grd.nextroute.testsupport.MutableClock;
import watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld;
import watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld.TrainAt;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld.GANGNAM;
import static watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld.SAMSEONG;
import static watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld.SEOLLEUNG;
import static watoo.grd.nextroute.testsupport.subway.SubwayScenarioWorld.YEOKSAM;

/**
 * 지하철 한 번 타는 이야기를 <b>처음부터 끝까지</b> 실제 DB·Redis 위에서 돌린다.
 *
 * <p>기존 IT 는 부품 하나씩만 본다 — 링은 전이 로그와 도착 캐시를 mock 으로 막는다. 그래서
 * "위치 응답 → 전이 추적 → 출발 색인 → 사용자 선택 고정 → 링 → push" 가 실제로 이어지는지는 아무도 확인하지
 * 않았다. 이 시험은 그 사슬을 잇는다. 고정은 탑승 중 열차 선택으로 만든다(모션 자동 판정기는 2026-10 제거).
 *
 * <p>가짜는 바깥 경계에만 둔다: 서울 위치·도착 API(피드를 손으로 만든다), FCM(발송 포트 mock),
 * 검색 결과 스냅샷, 시계. 그 안쪽은 전부 진짜다.
 *
 * <p>수집기({@code RealtimeSubwayPositionCollector})를 거치지 않고 추적기에 바로 넣는 이유 —
 * 수집기는 운행시간을 벽시계로 판정해 새벽에 돌리면 조용히 아무것도 안 한다. 스케줄러는 전부
 * {@code @Profile("worker")} 라 이 컨텍스트에서 뜨지 않고, tick 은 시험이 직접 부른다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        // 잠금화면 갱신 대기열. 발송 창구는 아래 @MockBean LiveActivityPushPort 가 대신한다.
        "journey.subway.ring.push.enabled=true"
})
@DisplayName("지하철 한 번 타기 — 사용자 시나리오")
class SubwayRideScenarioIT {

    private static final String SESSION_ID = "sess-subway-ride-scenario-it";
    private static final String PATH_KEY = "0";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 평일 아침. 영업일 경계(04:00)와 멀리 둔다. */
    private static final Instant MORNING =
            LocalDateTime.of(2026, 9, 28, 8, 30, 0).atZone(SEOUL).toInstant();

    /**
     * 전이 로그·링·도착 캐시가 전부 Redis 에 산다. 운영 설정은 {@code REDIS_HOST} 에 기본값이 없어
     * 로컬 Redis 에 기대면 환경마다 깨진다 — {@code RedisSubwayRingAdapterIT} 처럼 컨테이너를 띄운다.
     * 덤으로 개발용 Redis 의 키와 섞이지 않는다.
     */
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

    private static final String TRAIN_A = "2100";
    private static final String TRAIN_B = "2102";

    @TestConfiguration
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock scenarioClock() {
            return new MutableClock(MORNING, SEOUL);
        }
    }

    @Autowired MutableClock clock;
    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayTrainSelectionTxService selectionTxService;
    @Autowired JourneyLegRepository legRepository;
    @Autowired SubwayRingUpdateService ringService;
    @Autowired SubwayRingPushRelayService pushRelay;
    @Autowired SubwayRealtimeCachePort realtimeCache;
    @Autowired JourneyQueryUseCase journeyQuery;
    @Autowired ManageLiveActivityTokenUseCase liveActivityTokens;
    @Autowired ApplyJourneyEventUseCase applyJourneyEvent;
    @Autowired SubwayTrainTransitionTracker tracker;
    @Autowired SubwayStationAdjacency adjacency;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired StringRedisTemplate redisTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    /** 9호선 시나리오(#482)만 완행 경로를 운영 OTP fixture 로 준다 — 시험 환경은 OTP 패턴을 적재하지 않는다. */
    @org.springframework.boot.test.mock.mockito.SpyBean
    watoo.grd.nextroute.application.subway.service.SubwayLineOrder lineOrder;
    @MockBean LiveActivityPushPort pushPort;

    private final SubwayScenarioWorld world = new SubwayScenarioWorld();
    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;
    private Long rideLegId;
    private int rideLegIndex;

    @BeforeEach
    void setUp() {
        clock.set(MORNING);
        world.clearTrainLogs(redisTemplate);
        world.seed(jdbcTemplate);
        adjacency.reload();

        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, gangnamToSamseong(),
                        MORNING.plusSeconds(3600))));
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        rideLegId = jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                 order by leg_index limit 1
                """, Long.class, journeyId);
        rideLegIndex = jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, rideLegId);
        pinBoardingStation();
    }

    @AfterEach
    void cleanUp() {
        world.clearTrainLogs(redisTemplate);
        if (journeyId != null) {
            jdbcTemplate.update("delete from subway_ring_push_outbox where journey_id = ?", journeyId);
            jdbcTemplate.update("delete from subway_live_activity_token where journey_id = ?", journeyId);
            jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
            jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        }
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> {
            jdbcTemplate.update("delete from user_push_token where user_id = ?", id);
            jdbcTemplate.update("delete from users where id = ?", id);
        });
        createdUserIds.clear();
        world.cleanUp(jdbcTemplate);
    }

    // ── 시나리오 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정 뒤: 선릉으로 갈수록 링이 차오르고, 도착하면 다음 역이 하차역 삼성으로 넘어가며, 매번 잠금화면이 갱신된다")
    void ringFillsTowardSeolleungAndLockScreenFollows() {
        rushHourUntilConfirmed();
        liveActivityOn();

        // 역삼을 떠났다 — 링이 역삼 구간을 소화하고 선릉을 겨눈다.
        SubwayGuidance leftYeoksam = ringTick(180,
                world.arrivalRow(TRAIN_B, YEOKSAM, "2", null, sec(168)),
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "99", 110, sec(168)));
        assertThat(leftYeoksam.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(leftYeoksam.nextStationName()).isEqualTo(SEOLLEUNG.name());
        assertThat(leftYeoksam.alightingStationName()).isEqualTo(SAMSEONG.name());
        assertThat(leftYeoksam.remainingStations()).isEqualTo(2);

        // 선릉까지 110초 — 도착 예정이 "그때부터 110초"로 절대 시각이 된다.
        SubwayGuidance enRoute = ringTick(185,
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "99", 110, sec(168)));
        assertThat(enRoute.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(enRoute.estimatedNextStationAt()).isEqualTo(sec(168 + 110));

        // 진입 — 지금 화면 값에서 0.95 까지 짧은 직선(#483). 앵커는 화면 자리 그대로, 몇 초 뒤 0.95 에 닿는다.
        SubwayGuidance entering = ringTick(240,
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "0", null, sec(235)));
        assertThat(entering.progressTarget()).isEqualByComparingTo("0.95");
        assertThat(entering.segmentProgress()).isLessThan(entering.progressTarget());
        assertThat(entering.estimatedNextStationAt()).isAfter(sec(240));

        SubwayGuidance arrived = ringTick(285,
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "1", null, sec(280)));
        assertThat(arrived.segmentProgress()).isEqualByComparingTo("1.00");

        SubwayGuidance lastLeg = ringTick(290,
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "1", null, sec(280)));
        assertThat(lastLeg.nextStationName()).isEqualTo(SAMSEONG.name());
        assertThat(lastLeg.remainingStations()).isEqualTo(1);

        // 잠금화면: 기기 주소와 Activity 주소가 각자 제자리로, content-state 는 {name, props} 두 겹.
        ArgumentCaptor<JsonNode> contentState = ArgumentCaptor.forClass(JsonNode.class);
        then(pushPort).should(atLeast(5)).send(eq("device-token"), eq("activity-token"),
                eq("update"), anyLong(), contentState.capture());
        JsonNode last = contentState.getValue();
        assertThat(last.has("name")).isTrue();
        assertThat(last.get("props").isTextual()).as("props 는 JSON 문자열이다 — 객체면 앱이 조용히 무시한다").isTrue();
    }

    @Test
    @DisplayName("도착 안내 목록에 내 열차가 안 뜬다 — 위치 기록으로 선릉을 지난 걸 알고 다음 역을 넘기되, 남은 초는 지어내지 않는다")
    void ringFallsBackToPositionWhenArrivalListMissesOurTrain() {
        rushHourUntilConfirmed();

        // 도착 목록에는 앞차 A 만 있고 B 는 없다. 위치 피드는 B 가 선릉에 닿은 것을 본다.
        at(280);
        observe(train(TRAIN_A, SAMSEONG, SubwayTrainStatus.ARRIVED, 270),
                train(TRAIN_B, SEOLLEUNG, SubwayTrainStatus.ARRIVED, 275));
        SubwayGuidance reachedSeolleung = ringTick(285,
                world.arrivalRow(TRAIN_A, SAMSEONG, "1", null, sec(270)));
        assertThat(reachedSeolleung.nextStationName()).isEqualTo(SEOLLEUNG.name());
        assertThat(reachedSeolleung.segmentProgress()).isEqualByComparingTo("1.00");
        assertThat(reachedSeolleung.observedAt()).as("근거는 위치 기록의 선릉 도착").isEqualTo(sec(275));

        SubwayGuidance towardSamseong = ringTick(290,
                world.arrivalRow(TRAIN_A, SAMSEONG, "1", null, sec(270)));
        assertThat(towardSamseong.nextStationName()).isEqualTo(SAMSEONG.name());
        assertThat(towardSamseong.remainingStations()).isEqualTo(1);
        assertThat(towardSamseong.estimatedNextStationAt()).as("위치에는 남은 초가 없다").isNull();
    }

    @Test
    @DisplayName("실시간이 끊겼다 — 5분이 지나 시간표상 삼성에 닿았을 때도 링은 마지막 관측 다음 역(선릉)에서 멈추고, 진행률·도착 예정을 지어내지 않는다")
    void ringFreezesWhenRealtimeGoesSilent() {
        rushHourUntilConfirmed();

        // 도착 목록이 비었다. 위치 기록의 마지막은 "B 가 역삼에 섰다"(+140) 뿐이다 — 40초 전 도착은 사실이라 NORMAL.
        SubwayGuidance silent = ringTick(180);
        assertThat(silent.nextStationName()).isEqualTo(YEOKSAM.name());
        assertThat(silent.quality()).isEqualTo(SubwayRingQuality.NORMAL);

        // 5분 동안 아무 관측도 없다. 시간표대로라면 벌써 삼성이다.
        at(180 + 5 * 60);
        ringService.runOnce();
        SubwayGuidance later = guidance();

        assertThat(later.nextStationName()).as("관측 없이 역을 건너뛰지 않는다").isEqualTo(SEOLLEUNG.name());
        assertThat(later.quality()).isEqualTo(SubwayRingQuality.STALE);
        assertThat(later.segmentProgress()).isEqualByComparingTo("0");
        assertThat(later.estimatedNextStationAt()).isNull();
        assertThat(later.observedAt()).as("마지막 실제 관측 시각이 그대로다").isEqualTo(sec(140));
        assertThat(later.progressValidUntil()).as("앱이 보간할 여유가 없다")
                .isBeforeOrEqualTo(later.progressBaseAt());
    }

    @Test
    @DisplayName("삼성에서 내리고 여정을 끝냈다 — 잠금화면에 종료 프레임이 마지막 화면 그대로 나가고, 고정 기록은 남는다")
    void finishingTheJourneyEndsTheLiveActivity() {
        rushHourUntilConfirmed();
        liveActivityOn();
        ringTick(180,
                world.arrivalRow(TRAIN_B, YEOKSAM, "2", null, sec(168)),
                world.arrivalRow(TRAIN_B, SEOLLEUNG, "99", 110, sec(168)));
        ringTick(185, world.arrivalRow(TRAIN_B, SEOLLEUNG, "99", 110, sec(168)));

        at(400);
        long version = journeyQuery.get(userId, journeyId).orElseThrow().commandVersion();
        applyJourneyEvent.apply(userId, journeyId, new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.JOURNEY_COMPLETED, version, 1, null, null, clock.instant()));
        at(405);
        pushRelay.runOnce();

        then(pushPort).should().send(eq("device-token"), eq("activity-token"), eq("end"),
                anyLong(), any());
        assertThat(bindingStatus()).as("확정은 감사·복구용 사실이라 지우지 않는다").isEqualTo("CONFIRMED");
    }

    /**
     * 출근길 강남 승강장. A 가 먼저 떠나고 약 2분 뒤 B 가 떠난다. 사용자는 B 를 타고 앱에서 B 를 고른다(탑승 중 선택).
     * 위치 피드는 그대로 흘려 둔다 — 링의 위치 폴백과 출발 색인이 이 기록을 읽는다.
     */
    private void rushHourUntilConfirmed() {
        at(-145);
        observe(train(TRAIN_A, GANGNAM, SubwayTrainStatus.ARRIVED, -150));
        at(-85);
        observe(train(TRAIN_A, GANGNAM, SubwayTrainStatus.DEPARTED, -90));
        at(-60);
        observe(train(TRAIN_A, GANGNAM, SubwayTrainStatus.DEPARTED, -90),
                train(TRAIN_B, GANGNAM, SubwayTrainStatus.ARRIVED, -65));
        at(30);
        observe(train(TRAIN_A, YEOKSAM, SubwayTrainStatus.ARRIVED, 28),
                train(TRAIN_B, GANGNAM, SubwayTrainStatus.DEPARTED, 20));

        at(35);
        selectAboard(TRAIN_B);
        assertBinding("CONFIRMED", TRAIN_B);

        at(150);
        observe(train(TRAIN_A, SEOLLEUNG, SubwayTrainStatus.ARRIVED, 145),
                train(TRAIN_B, YEOKSAM, SubwayTrainStatus.ARRIVED, 140));
    }

    // ── 사용자 행동 ─────────────────────────────────────────────────────────

    // ── #482: 급행 계획 + 일반 열차 (subway-express PR-S4) ──────────────────────

    @Test
    @DisplayName("#482 9호선 급행 계획(가양→염창)인데 일반 열차를 골랐다 — 다음 역은 증미, 남은 역 3(급행 체인이면 염창·1)")
    void line9ExpressPlanButNormalTrain() {
        var fixture = watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures.fixtureLineOrder();
        org.mockito.Mockito.doAnswer(inv -> "1009".equals(inv.getArgument(0))
                        ? fixture.allStopPath(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                        inv.getArgument(3))
                        : inv.callRealMethod())
                .when(lineOrder).allStopPath(any(), any(), any(), any());
        List<Long> seeded = seedLine9Stations();
        try {
            String gayang = "1009000907";
            String jeungmi = "1009000908";
            given(snapshotPort.find("sess-line9-482", PATH_KEY)).willReturn(Optional.of(
                    new RouteSearchSnapshotPort.StoredPath(null, new PathResult(1, null, List.of(
                            SubPathFixture.subway(4).names("가양", "염창").subwayStations(900907, 900910).wayCode(1)
                                    .lanes(SubPathFixture.subwayLane("9호선", 9))
                                    .stations(new StationResult(0, "900907", "가양", null, null),
                                            new StationResult(1, "900910", "염창", null, null))
                                    .trainKind("EXPRESS")
                                    .build(),
                            SubPathFixture.walk(5).names("염창", "목적지").distance(300.0).build()), List.of()),
                            MORNING.plusSeconds(3600))));
            userId = newUser();
            journeyId = startJourneyUseCase.start(userId, new StartJourneyCommand("sess-line9-482", PATH_KEY, false))
                    .journeyId();
            rideLegId = jdbcTemplate.queryForObject("""
                    select id from journey_leg where journey_id = ? and type = 'RIDE_SUBWAY'
                     order by leg_index limit 1
                    """, Long.class, journeyId);
            jdbcTemplate.update("update journey_leg set start_stop_id = ? where id = ?", gayang, rideLegId);
            // 사용자가 일반 열차 9100 을 탔다(선택 시 판정 NORMAL — 플래그와 무관)
            selectionTxService.bind(legRepository.findById(rideLegId).orElseThrow(),
                    new SubwayTrainSelectionTxService.ChosenTrain("9100", LocalDate.ofInstant(MORNING, SEOUL),
                            "1009", "상행", true,
                            watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind.NORMAL, null, null));

            at(10);
            realtimeCache.saveSnapshot(watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot.builder()
                    .collectedAt(LocalDateTime.ofInstant(sec(10), SEOUL).atZone(SEOUL)
                            .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                    .trains(List.of(watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain.builder()
                            .trainNo("9100").stationId(jeungmi).stationName("증미").lineId("1009").direction("상행")
                            .arrivalCode("99").arrivalSeconds(60).trainType("일반")
                            .receivedAt(LocalDateTime.ofInstant(sec(5), SEOUL)
                                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                            .build()))
                    .build(), 60);
            ringService.runOnce();
            SubwayGuidance g = guidance();

            assertThat(g).isNotNull();
            assertThat(g.nextStationName()).isEqualTo("증미");
            assertThat(g.remainingStations()).isEqualTo(3);
            assertThat(g.alightingStationName()).isEqualTo("염창");
            assertThat(g.trainKind()).isEqualTo("NORMAL");
            assertThat(g.trainLabel()).isEqualTo("일반");
        } finally {
            seeded.forEach(id -> jdbcTemplate.update("delete from subway_station where id = ?", id));
        }
    }

    /** 9호선 역 마스터가 없으면 넣는다(로컬 DB 는 개발 역 마스터를 함께 쓴다). */
    private List<Long> seedLine9Stations() {
        List<Long> ids = new ArrayList<>();
        for (var station : watoo.grd.nextroute.application.subway.service.SubwayExpressFixtures.stations("1009")) {
            Integer exists = jdbcTemplate.queryForObject(
                    "select count(*) from subway_station where line_id = '1009' and statn_id = ?", Integer.class,
                    station.getStationId());
            if (exists == null || exists == 0) {
                ids.add(jdbcTemplate.queryForObject("""
                        insert into subway_station (created_at, updated_at, line_id, statn_id, statn_nm)
                        values (now(), now(), '1009', ?, ?) returning id
                        """, Long.class, station.getStationId(), station.getStationName()));
            }
        }
        return ids;
    }

    /** 이미 탄 열차를 고른다(떠난 열차·탑승 중 재선택) — 링이 승차역이 아니라 다음 역부터 센다. */
    private void selectAboard(String trainNo) {
        JourneyLeg ride = legRepository.findById(rideLegId).orElseThrow();
        selectionTxService.bind(ride, new SubwayTrainSelectionTxService.ChosenTrain(trainNo,
                LocalDate.ofInstant(MORNING, SEOUL), SubwayScenarioWorld.LINE, SubwayScenarioWorld.DIRECTION, true));
    }

    /** 앱이 로그인 때 기기 push 토큰을 등록해 둔다. Live Activity 토큰은 그 기기에 딸린다. */
    private void registerDevice() {
        jdbcTemplate.update("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment,
                     enabled, last_seen_at)
                values (now(), now(), ?, 'IOS', 'FCM', 'device-token', 'PRODUCTION', true, now())
                """, userId);
    }

    /** Live Activity 를 켰다. 발송은 성공한다고 둔다. */
    private void liveActivityOn() {
        registerDevice();
        liveActivityTokens.register(userId, journeyId, "activity-token", "device-token",
                PushEnvironment.PRODUCTION);
        given(pushPort.send(any(), any(), any(), anyLong(), any()))
                .willReturn(new LiveActivityPushPort.PushResult(true, null));
    }

    // ── 세계 ────────────────────────────────────────────────────────────────

    private void at(int second) {
        clock.set(MORNING.plusSeconds(second));
    }

    private void observe(TrainAt... trains) {
        tracker.observe(world.positionFeed(clock.instant(), trains));
    }

    private Instant sec(int second) {
        return MORNING.plusSeconds(second);
    }

    /** 도착 스냅샷을 갈아 끼우고 링·발송 tick 을 한 번씩 돌린 뒤, 앱이 받는 화면을 돌려준다. */
    private SubwayGuidance ringTick(int second, SubwayRealtimeTrain... rows) {
        at(second);
        realtimeCache.saveSnapshot(world.arrivalFeed(clock.instant(), rows), 60);
        ringService.runOnce();
        pushRelay.runOnce();
        return guidance();
    }

    private SubwayGuidance guidance() {
        return journeyQuery.get(userId, journeyId).orElseThrow().subwayGuidance();
    }

    private String bindingStatus() {
        return jdbcTemplate.queryForObject(
                "select status from subway_leg_binding where journey_leg_id = ?", String.class, rideLegId);
    }

    private TrainAt train(String no, SubwayScenarioWorld.Station station, SubwayTrainStatus status,
                          int reportedAtSecond) {
        return new TrainAt(no, station, status, MORNING.plusSeconds(reportedAtSecond));
    }

    // ── 확인 ────────────────────────────────────────────────────────────────

    private void assertBinding(String status, String trainNo) {
        Map<String, Object> row = jdbcTemplate.queryForList("""
                select status, train_no from subway_leg_binding where journey_leg_id = ?
                """, rideLegId).stream().findFirst().orElse(Map.of());
        assertThat(row)
                .as(() -> "고정 행=" + row + "\n근거=" + evidenceRows() + "\n출발 색인=" + departureKeys())
                .containsEntry("status", status)
                .containsEntry("train_no", trainNo);
    }

    private List<Map<String, Object>> evidenceRows() {
        return jdbcTemplate.queryForList("""
                select kind, event_type, evidence_sequence, chosen_train_no, detail
                  from subway_binding_event where journey_id = ? order by id
                """, journeyId);
    }

    private Set<String> departureKeys() {
        return redisTemplate.keys("subway:dep:*:" + SubwayScenarioWorld.LINE + ":*");
    }

    // ── 시드 ────────────────────────────────────────────────────────────────

    /**
     * 시험 환경은 정적 데이터가 꺼져 있어 승차역 {@code statnId} 와 방향이 비어 있다.
     * 운영에서는 여정 시작 때 채워지는 값이라 여기서 손으로 채운다.
     */
    private void pinBoardingStation() {
        jdbcTemplate.update("update journey_leg set start_stop_id = ? where id = ?",
                GANGNAM.id(), rideLegId);
    }

    /** 강남 → 역삼 → 선릉 → 삼성. 역 이름은 무대와 같은 상수에서 뽑는다. */
    private PathResult gangnamToSamseong() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(8).names(GANGNAM.name(), SAMSEONG.name())
                                .subwayStations(900222, 900219).wayCode(SubwayScenarioWorld.WAY_CODE)
                                .lanes(SubPathFixture.subwayLane("2호선", 2))
                                .stations(
                                        new StationResult(0, "900222", GANGNAM.name(), null, null),
                                        new StationResult(1, "900221", YEOKSAM.name(), null, null),
                                        new StationResult(2, "900220", SEOLLEUNG.name(), null, null),
                                        new StationResult(3, "900219", SAMSEONG.name(), null, null))
                                .build(),
                        SubPathFixture.walk(5).names(SAMSEONG.name(), "목적지").distance(300.0).build()),
                List.of());
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id
                """, Long.class);
        createdUserIds.add(id);
        return id;
    }

    private void deleteJourneysOf(Long id) {
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("""
                delete from journey_leg_route_option where journey_leg_id in (
                    select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, id);
        for (String table : List.of("journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }
}
