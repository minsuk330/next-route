package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import watoo.grd.nextroute.application.bus.BusArrivalInfoFixtures;
import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlFeatureVector;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPrediction;
import watoo.grd.nextroute.application.route.port.out.MlArrivalPredictorPort.MlPredictionStatus;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.application.route.service.PredictionSupportService;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.infrastructure.adapter.out.api.bus.SearchTimeBusAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.gong.GongApiAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.mlpredictor.MlArrivalPredictorAdapter;
import watoo.grd.nextroute.infrastructure.adapter.out.api.subway.SeoulSubwayApiAdapter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 같은 것을 묻는 여정이 여럿이어도 외부는 한 번만 부르는지 본다.
 *
 * <p>Phase 4 ⑤ 의 통과 기준이 이 한 문장이다 — <b>외부 호출 수가 여정 수가 아니라 서로 다른
 * 관심(정류장·노선) 수에 가까워야 한다.</b> 같은 정류장에서 같은 버스를 기다리는 사용자 100명 때문에
 * 100번을 부르면 API 할당량이 먼저 바닥난다.
 *
 * <p><b>mock 은 가장 아래 delegate 만 한다.</b> 포트를 목킹하면 캐시 데코레이터가 통째로 빠져 시험이
 * 아무것도 증명하지 못한다. 그래서 {@code SearchTimeBusAdapter}·{@code MlArrivalPredictorAdapter}·
 * {@code SeoulSubwayApiAdapter} 를 바꾸고, 그 위의 캐시 계층은 실제 Redis 와 함께 돌린다.
 *
 * <p>캐시가 상류에 매달린다는 성질(버스 위치 스냅샷이 갱신되면 ML 캐시 키가 죽는다)은
 * {@code MlFeatureCacheKeyTest} 가 단위로 못박는다. 여기서 컨텍스트를 하나 더 띄우지 않는 이유다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "ml.predictor.enabled=true"
})
@DisplayName("평가 중복 조회 합치기")
class JourneyEvaluationDedupeIT {

    private static final String SESSION_ID = "sess-dedupe-it";
    private static final String PATH_KEY = "0";

    /** 세 여정이 모두 같은 정류장에서 같은 노선을 기다린다 — 관심은 하나다. */
    private static final String STOP_ID = "ST-G";
    private static final String ROUTE_ID = "BL-721";
    private static final int BOARDING_SEQ = 3;

    private static final DateTimeFormatter DATA_TM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 캐시 계층이 실제 Redis 위에서 도는지까지 본다 — 직렬화·TTL 이 이 시험의 대상이다.
     *
     * <p>개발자 컨테이너 대신 전용 컨테이너를 쓰는 이유는, 캐시 키가 남아 있으면 첫 호출이 삼켜져
     * 호출 수 단언이 조용히 통과해 버리기 때문이다. {@code RouteSearchCacheIT} 와 같은 방식이다.
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

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired JourneyDueClaimService claimService;
    @Autowired JourneyEvaluationService evaluationService;
    @Autowired SubwayRealtimeArrivalReader subwayArrivalReader;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired RedisTemplate<String, String> redisTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean SearchTimeBusAdapter busDelegate;
    @MockBean MlArrivalPredictorAdapter mlDelegate;
    @MockBean SeoulSubwayApiAdapter subwayDelegate;
    @MockBean PredictionSupportService supportService;
    @MockBean GongApiAdapter holidayCalendar;

    private final List<Long> createdUserIds = new ArrayList<>();
    private final List<Long> journeyIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clearCaches();
        givenRouteStopMapping();
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
        journeyIds.clear();
        deleteRouteStopMapping();
        clearCaches();
    }

    @Nested
    @DisplayName("버스 환승")
    class Bus {

        @Test
        @DisplayName("같은 정류장·노선을 보는 여정 셋이 ML 을 한 번만 부른다")
        void sharesModelCallAcrossJourneys() {
            givenBusProviders();
            startJourneys(3);

            evaluateAll();

            verify(mlDelegate, times(1)).predict(anyList());
        }

        @Test
        @DisplayName("실시간 조회도 한 번이다 — 기존 버스 캐시가 그대로 동작한다")
        void sharesRealtimeCallsAcrossJourneys() {
            givenBusProviders();
            startJourneys(3);

            evaluateAll();

            verify(busDelegate, times(1)).getArrInfoByStop(STOP_ID, ROUTE_ID, BOARDING_SEQ);
            verify(busDelegate, times(1)).getBusPosByRtid(ROUTE_ID);
        }

        @Test
        @DisplayName("합쳐도 여정마다 판정이 남는다 — 한 번 불렀다고 답이 사라지면 안 된다")
        void everyJourneyStillGetsItsOwnAssessment() {
            givenBusProviders();
            startJourneys(3);

            evaluateAll();

            for (Long journeyId : journeyIds) {
                assertThat(storedSource(journeyId)).isEqualTo("MODEL");
                assertThat(storedNextBusArrivalAt(journeyId)).isNotNull();
            }
        }
    }

    @Nested
    @DisplayName("지하철 역별 조회")
    class Subway {

        @Test
        @DisplayName("같은 역을 세 번 물어도 API 는 한 번만 부른다")
        void sharesStationCall() {
            given(subwayDelegate.getRealtimeArrivalByStation("강남"))
                    .willReturn(List.of(subwayArrival()));

            for (int i = 0; i < 3; i++) {
                subwayArrivalReader.arrivalsByStation("1002", "923", "강남", 1);
            }

            verify(subwayDelegate, times(1)).getRealtimeArrivalByStation("강남");
        }

        @Test
        @DisplayName("다른 역은 따로 부른다 — 합치는 단위는 역이다")
        void differentStationsAreSeparateCalls() {
            given(subwayDelegate.getRealtimeArrivalByStation(anyString()))
                    .willReturn(List.of(subwayArrival()));

            subwayArrivalReader.arrivalsByStation("1002", "923", "강남", 1);
            subwayArrivalReader.arrivalsByStation("1002", "924", "역삼", 1);

            verify(subwayDelegate, times(1)).getRealtimeArrivalByStation("강남");
            verify(subwayDelegate, times(1)).getRealtimeArrivalByStation("역삼");
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 버스 provider 응답.
     *
     * <p>도착 응답의 노선이 우리가 찾는 노선과 달라 실시간으로는 못 잡는다 → 모델 경로로 내려간다.
     * 빈 목록으로 만들지 않는 이유는 <b>빈 응답은 캐시에 저장되지 않기 때문</b>이다(장애로 인한 빈
     * 결과를 고착시키지 않으려는 규칙). 그러면 도착 조회가 여정마다 다시 나가 시험의 뜻이 흐려진다.
     */
    private void givenBusProviders() {
        given(supportService.support(ROUTE_ID)).willReturn(PredictionSupportService.Support.SUPPORTED);
        given(busDelegate.getArrInfoByStop(STOP_ID, ROUTE_ID, BOARDING_SEQ))
                .willReturn(BusQueryResult.ok(List.of(
                        BusArrivalInfoFixtures.arrivalWithSectionTime(60, 240))));
        given(busDelegate.getBusPosByRtid(ROUTE_ID))
                .willReturn(BusQueryResult.ok(List.of(position("veh-1"))));
        willAnswer(call -> {
            List<MlFeatureVector> vectors = call.getArgument(0);
            return vectors.stream()
                    .map(vector -> new MlPrediction(vector.requestId(),
                            MlPredictionStatus.AVAILABLE, 300.0, "bus-eta-v3"))
                    .toList();
        }).given(mlDelegate).predict(anyList());
    }

    /** 지금 관측된 차량. 환승 정류장(seq 3) 앞에 있어야 예측 대상이 된다. */
    private BusPositionInfo position(String vehicleId) {
        String dataTm = ZonedDateTime.now(SEOUL).format(DATA_TM);
        return new BusPositionInfo(
                vehicleId, 60, 1, 500.0, 10000.0, "0", "sec-1",
                dataTm, "서울70사1234", 0, 120, "ST-A",
                null, null, "0", "0", 1000.0, "ST-B",
                2, null, 127.01, 37.51, "1");
    }

    private watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo subwayArrival() {
        return new watoo.grd.nextroute.application.subway.dto.SubwayArrivalInfo(
                "923", "강남", "1002", "상행", null, null, 0, "0", null, null,
                "일반", 120, "1001", null, "성수", "전역 출발", "0", "1002", null,
                ZonedDateTime.now(SEOUL).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")),
                null, "N");
    }

    private void startJourneys(int count) {
        for (int i = 0; i < count; i++) {
            Long userId = newUser();
            JourneySnapshot journey = startJourneyUseCase.start(userId,
                    new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
            journeyIds.add(journey.journeyId());
            boardFirstBus(userId, journey.journeyId());
            resolveStopIdentity(journey.journeyId());
        }
    }

    /** 우리 여정만 평가한다. 다른 시험이 남긴 여정이 섞이면 호출 수 단언이 흔들린다. */
    private void evaluateAll() {
        List<JourneyDueClaim> claims = claimService.claimDue().stream()
                .filter(claim -> journeyIds.contains(claim.journeyId()))
                .toList();
        assertThat(claims).hasSize(journeyIds.size());
        claims.forEach(evaluationService::evaluate);
    }

    /** 대기(0) → 주행(1) 전이. 주행 단계가 시작돼야 환승 정류장 도착 시각이 정해진다. */
    private void boardFirstBus(Long userId, Long journeyId) {
        var outcome = applyJourneyEventUseCase.apply(userId, journeyId,
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        1L, 1, 0, 1, Instant.now()));
        assertThat(outcome.result().name()).isEqualTo("APPLIED");
    }

    /**
     * 갈아탈 정류장과 승차 순번을 채운다. 없으면 예측기가 "노선·정류장 미해석"으로 조회를 건너뛴다.
     */
    private void resolveStopIdentity(Long journeyId) {
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = ?
                where journey_id = ? and type = 'WAIT_BUS' and leg_index > 0
                  and start_stop_id is null
                """, STOP_ID, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option set boarding_seq = ?
                where journey_leg_id in (
                    select id from journey_leg where journey_id = ? and deleted_at is null)
                  and boarding_seq is null
                """, BOARDING_SEQ, journeyId);
    }

    /** 예측기는 (노선, 정류장, 순번) 조합이 실제로 있는지 DB 로 확인한 뒤에야 조회한다. */
    private void givenRouteStopMapping() {
        deleteRouteStopMapping();
        jdbcTemplate.update("""
                insert into bus_route (created_at, updated_at, route_id, route_name)
                values (now(), now(), ?, '721')
                """, ROUTE_ID);
        jdbcTemplate.update("""
                insert into bus_stop (created_at, updated_at, stop_id, stop_name)
                values (now(), now(), ?, '정류장G')
                """, STOP_ID);
        jdbcTemplate.update("""
                insert into bus_route_stop (created_at, updated_at, route_id, stop_id, seq)
                values (now(), now(), ?, ?, ?)
                """, ROUTE_ID, STOP_ID, BOARDING_SEQ);
    }

    private void deleteRouteStopMapping() {
        jdbcTemplate.update("delete from bus_route_stop where route_id = ?", ROUTE_ID);
        jdbcTemplate.update("delete from bus_route where route_id = ?", ROUTE_ID);
        jdbcTemplate.update("delete from bus_stop where stop_id = ?", STOP_ID);
    }

    /** 앞선 시험이 남긴 캐시가 이번 시험의 첫 호출을 삼키지 않게 한다. */
    private void clearCaches() {
        for (String pattern : List.of("ml:pred:v1:*", "subway:arr:station:*", "bus:search:*")) {
            Set<String> keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        }
    }

    private String storedSource(Long journeyId) {
        return jdbcTemplate.queryForObject("""
                select source from transfer_connection
                where journey_id = ? and transfer_index = 0 and deleted_at is null
                """, String.class, journeyId);
    }

    private Instant storedNextBusArrivalAt(Long journeyId) {
        return jdbcTemplate.queryForObject("""
                select next_bus_arrival_at from transfer_connection
                where journey_id = ? and transfer_index = 0 and deleted_at is null
                """, Instant.class, journeyId);
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
        // 알림 outbox 는 여정을 FK 로 잡고 있다. 평가가 위험을 감지하면 여기에도 행이 생기므로
        // 여정보다 먼저 지운다.
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    /** 환승이 실시간 시야(20분) 안에 있도록 첫 버스를 5분으로 둔다. */
    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(5).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops(STOP_ID, "ST-Z")
                                .lanes(SubPathFixture.busLane("721", ROUTE_ID)).build()),
                List.of());
    }
}
