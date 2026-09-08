package watoo.grd.nextroute.application.journey;

import watoo.grd.nextroute.application.route.routing.RoutingCacheContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.RemainingRouteCachePort;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.RouteSearchResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * ③이 빈손일 때 ④가 <b>실제 여정 위에서</b> 도는지.
 *
 * <p>여기서 지키는 것 셋.
 *
 * <ul>
 *   <li>③이 낼 것이 없으면 ④가 받아 제안을 만든다 — 사다리가 이어져 있다</li>
 *   <li><b>캐시가 맞으면 유료 검색을 부르지 않는다.</b> 이 칸의 요금이 거기서 갈린다</li>
 *   <li>미해석 정류장이면 검색을 아예 부르지 않는다 — 캐시 키가 섞여 다른 정류장의 경로를
 *       서로 돌려주게 된다</li>
 * </ul>
 *
 * <p>{@link TransferStopResolver} 를 {@link MockBean} 으로 둔다. 정류장·노선 해석은 정적 노선표를
 * 보는데 시험 DB 에는 그 표가 없다. 여기서 확인하려는 것은 표의 내용이 아니라 <b>보강 단계를
 * 거치는지</b>이므로, 표만 가짜로 주고 보강 자체는 진짜를 태운다 — 보강을 빼먹으면 후보가 전부
 * "예측 없음"이 되는데 그 증상은 예측 API 문제처럼 보인다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true"
})
@DisplayName("남은 구간 재검색 제안")
class RerouteRemainingPathIT {

    private static final String SESSION_ID = "sess-reroute-remaining-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired RerouteProposalService rerouteProposalService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean RerouteCooldownPort cooldownPort;
    @MockBean RemainingRouteCachePort remainingRouteCache;
    @MockBean TransferStopResolver transferStopResolver;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, plannedPath(), Instant.now().plusSeconds(600))));
        given(cooldownPort.tryAdmit(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any())).willReturn(true);
        givenStaticTables();
        userId = newUser();
        journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("③이 빈손이면 ④가 받아 제안을 만든다")
    void remainingPathProposalIsCreatedWhenRouteOptionIsEmpty() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        // 지금 노선은 20분 뒤에 온다. 대안 노선이 없으니 ③은 낼 것이 없다.
        givenPrediction("BL-721", readyAt.plusSeconds(1200));
        // 다른 경로의 버스는 1분 뒤. 목적지까지 훨씬 빠르다.
        givenPrediction("BL-360", readyAt.plusSeconds(60));
        given(remainingRouteCache.find(any(RoutingCacheContext.class), anyString(), anyDouble(), anyDouble()))
                .willReturn(Optional.empty());
        given(odSayApiPort.searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong()))
                .willReturn(new RouteSearchResult(0, 1, 0, 0, 0, List.of(alternativePath())));

        Optional<RerouteProposalView> proposal =
                rerouteProposalService.request(userId, journey.journeyId(), 0);

        assertThat(proposal).get().satisfies(view -> {
            assertThat(view.candidates()).hasSize(1);
            assertThat(view.candidates().get(0).kind().name()).isEqualTo("REMAINING_PATH");
            assertThat(view.candidates().get(0).candidateId())
                    .as("저장 시점에 서버가 UUID 를 부여한다")
                    .isNotBlank();
            assertThat(view.candidates().get(0).display().etaGainSec())
                    .as("지금 경로보다 빨라야 제안이 된다")
                    .isGreaterThanOrEqualTo(300);
        });
        assertThat(activeProposalCount()).isEqualTo(1);
        verify(odSayApiPort, times(1))
                .searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());
    }

    @Test
    @DisplayName("캐시가 맞으면 유료 검색을 부르지 않는다")
    void cacheHitSkipsPaidSearch() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));
        givenPrediction("BL-360", readyAt.plusSeconds(60));
        given(remainingRouteCache.find(any(RoutingCacheContext.class), anyString(), anyDouble(), anyDouble()))
                .willReturn(Optional.of(List.of(alternativePath())));

        Optional<RerouteProposalView> proposal =
                rerouteProposalService.request(userId, journey.journeyId(), 0);

        assertThat(proposal).isPresent();
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("검색이 죽어도 예외가 아니라 빈 결과다")
    void searchFailureIsEmptyResult() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        givenPrediction("BL-721", readyAt.plusSeconds(1200));
        given(remainingRouteCache.find(any(RoutingCacheContext.class), anyString(), anyDouble(), anyDouble()))
                .willReturn(Optional.empty());
        org.mockito.BDDMockito.willThrow(new IllegalStateException("odsay down"))
                .given(odSayApiPort)
                .searchPath(anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyLong());

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0)).isEmpty();
        assertThat(activeProposalCount()).isZero();
    }

    @Test
    @DisplayName("미해석 정류장이면 검색을 부르지 않는다 — 캐시 키가 섞인다")
    void unresolvedStopSkipsSearch() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        jdbcTemplate.update("update journey_leg set start_stop_id = null where id = ?",
                firstWaitLegId());

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0)).isEmpty();
        verifyNoInteractions(odSayApiPort);
        verify(remainingRouteCache, never()).find(any(RoutingCacheContext.class), anyString(), anyDouble(), anyDouble());
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 정적 노선표를 가짜로 준다. 시험 DB 에는 표가 없어 이것 없이는 승·하차 순번이 비고, 그러면
     * 기준선도 후보도 "노선 미해석"으로 죽는다 — 재탐색이 아니라 준비물의 문제다.
     */
    private void givenStaticTables() {
        given(transferStopResolver.resolveStop(anyString())).willAnswer(call ->
                Optional.of(new TransferStopResolver.StopResolution(call.getArgument(0))));
        given(transferStopResolver.resolveRoute(anyString(), any())).willAnswer(call ->
                Optional.of(new TransferStopResolver.RouteResolution(
                        call.getArgument(0), call.getArgument(1), false, null)));
        given(transferStopResolver.resolveSeq(anyString(), anyString()))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(3)));
    }

    /** 평가가 근거를 채운 상태로 만든다. 실제로는 {@code TransferAssessmentWriter} 가 쓴다. */
    private void givenLiveTransfer(int transferIndex, Instant readyAt) {
        jdbcTemplate.update("""
                update transfer_connection
                   set status = 'AT_RISK', user_ready_at = ?, confidence = 'GOOD',
                       current_leg_arrival_at = ?, calculated_at = now()
                 where journey_id = ? and transfer_index = ? and deleted_at is null
                """, java.sql.Timestamp.from(readyAt), java.sql.Timestamp.from(readyAt),
                journey.journeyId(), transferIndex);
    }

    private void givenPrediction(String routeId, Instant arrivalAt) {
        given(predictTransferUseCase.predict(anyString(), eq(routeId),
                any(), any(), any(), anyLong()))
                .willAnswer(call -> TransferPredictionResult.available(
                        call.getArgument(0), routeId, call.getArgument(2),
                        TransferArrival.Source.REALTIME, Instant.now(), call.getArgument(3),
                        arrivalAt, null, null));
    }

    private long firstWaitLegId() {
        return jdbcTemplate.queryForObject("""
                select l.id from journey_leg l
                  join transfer_connection t
                    on t.journey_id = l.journey_id
                   and t.route_revision = l.route_revision
                   and t.to_leg_index = l.leg_index
                 where l.journey_id = ? and t.transfer_index = 0 and l.deleted_at is null
                """, Long.class, journey.journeyId());
    }

    private int activeProposalCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from reroute_proposal
                 where journey_id = ? and status = 'ACTIVE'
                """, Integer.class, journey.journeyId());
    }

    /**
     * 버스 둘, 환승 하나. 대기 단계마다 노선 후보가 <b>하나뿐</b>이라 ③은 낼 것이 없다 —
     * 그 상황을 받는 것이 ④의 존재 이유다.
     */
    private PathResult plannedPath() {
        return new PathResult(1,
                new PathInfo(60, 4000, 900, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .coords(127.00, 37.00, 127.10, 37.10)
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0)
                                .coords(127.10, 37.10, 127.20, 37.20).build(),
                        SubPathFixture.bus(40).names("정류장G", "정류장Z")
                                .coords(127.20, 37.20, 127.50, 37.50)
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }

    /** 재검색이 준 다른 경로. 같은 정류장에서 출발하지만 노선도 소요도 다르다. */
    private PathResult alternativePath() {
        return new PathResult(1,
                new PathInfo(25, 3800, 300, 0, "정류장G", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .coords(127.20, 37.20, 127.50, 37.50)
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("360", "BL-360")).build()),
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
                delete from reroute_proposal where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
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
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }
}
