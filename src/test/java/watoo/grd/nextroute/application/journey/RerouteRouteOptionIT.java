package watoo.grd.nextroute.application.journey;

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
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.RerouteProposalService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.routing.RideDurationResult;
import watoo.grd.nextroute.application.route.routing.RouteRideDurationPort;
import watoo.grd.nextroute.application.route.port.out.OdSayApiPort;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 같은 정류장 다른 노선 후보가 <b>실제 여정 위에서</b> 만들어지는지.
 *
 * <p>여기서 지키는 것 둘.
 *
 * <ul>
 *   <li><b>경로 검색을 한 번도 부르지 않는다.</b> Phase 6 ③의 완료 조건이다 — 승·하차 정류장이
 *       그대로라 물어볼 것은 "이 노선이 언제 오나"뿐이다</li>
 *   <li><b>환승이 둘 이상인 여정에서도 제안이 나온다.</b> 대기 단계 소요는 언제나 비어 있어서,
 *       "값이 없으면 포기"로 짜면 그런 여정은 영원히 0건이 된다(R1)</li>
 * </ul>
 *
 * <p>cooldown 은 {@link MockBean} 으로 연다. Redis 를 띄우는 것은 이 시험의 관심이 아니고,
 * 잠금의 계약은 어댑터 단위 시험이 이미 못 박는다. 여기서 실제 Redis 에 붙으면 잠금이 살아 있는
 * 동안 뒤따르는 시험이 이유 없이 거절된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.reroute.enabled=true"
})
@DisplayName("같은 정류장 다른 노선 제안")
class RerouteRouteOptionIT {

    private static final String SESSION_ID = "sess-reroute-route-option-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired RerouteProposalService rerouteProposalService;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;
    @MockBean OdSayApiPort odSayApiPort;
    @MockBean RerouteCooldownPort cooldownPort;
    // PR4c: 노선별 시간표 소요(OTP). 이 시험 환경엔 OTP 가 없으니 스텁으로 "소요를 얻었다"고 둔다.
    // 값(600)은 기준선·후보에 똑같이 승차 소요를 대체하므로 개선폭은 예측 시각 차이 그대로다.
    // 공정성 게이트가 승격을 막지 않도록 baseline·후보 모두 OK 로 답한다.
    @MockBean RouteRideDurationPort rideDurationPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, twoTransferPath(), Instant.now().plusSeconds(600))));
        given(cooldownPort.tryAdmit(anyLong(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyInt(), any())).willReturn(true);
        given(rideDurationPort.rideDurationSeconds(anyString(), anyString(), anyString(), any()))
                .willReturn(RideDurationResult.ok(600));
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
    @DisplayName("더 빠른 노선이 있으면 제안 한 건을 저장한다 — 경로 검색은 부르지 않는다")
    void fasterRouteIsProposedWithoutPathSearch() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        givenResolvedBoarding(firstWaitLegId(), "BL-721");
        givenAlternativeRoute(firstWaitLegId(), "BL-999", "999 번");
        // 지금 노선은 12분 뒤, 대안은 1분 뒤. 660초 빨라지므로 임계값(300)을 넘는다.
        givenPrediction("BL-370", readyAt.plusSeconds(720));
        givenPrediction("BL-721", readyAt.plusSeconds(720));
        givenPrediction("BL-999", readyAt.plusSeconds(60));

        Optional<RerouteProposalView> proposal =
                rerouteProposalService.request(userId, journey.journeyId(), 0);

        assertThat(proposal).get().satisfies(view -> {
            assertThat(view.candidates()).hasSize(1);
            assertThat(view.candidates().get(0).candidateId())
                    .as("저장 시점에 서버가 UUID 를 부여한다")
                    .isNotBlank();
            assertThat(view.candidates().get(0).display().etaGainSec()).isEqualTo(660);
        });
        assertThat(activeProposalCount()).isEqualTo(1);

        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("환승이 둘인 여정에서도 제안이 나온다 — 이후 대기 소요가 비어 있어도")
    void multiTransferJourneyStillProposes() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        givenResolvedBoarding(firstWaitLegId(), "BL-721");
        givenAlternativeRoute(firstWaitLegId(), "BL-999", "999 번");
        givenPrediction("BL-370", readyAt.plusSeconds(720));
        givenPrediction("BL-721", readyAt.plusSeconds(720));
        givenPrediction("BL-999", readyAt.plusSeconds(60));

        Optional<RerouteProposalView> proposal =
                rerouteProposalService.request(userId, journey.journeyId(), 0);

        assertThat(transferCount())
                .as("이 여정은 환승이 둘이다 — 그래서 이후 대기 단계가 남는다")
                .isEqualTo(2);
        assertThat(proposal)
                .as("대기 소요가 비었다고 포기하면 이 여정은 영원히 0건이다")
                .isPresent();
        assertThat(proposal.get().candidates().get(0).display().confidence())
                .as("이후 환승이 남아 있으면 절대 시각이 낙관적이다")
                .isEqualTo("APPROXIMATE");
    }

    @Test
    @DisplayName("개선폭이 임계값 미만이면 저장하지 않는다 — 예외가 아니다")
    void smallGainStoresNothing() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        givenResolvedBoarding(firstWaitLegId(), "BL-721");
        givenAlternativeRoute(firstWaitLegId(), "BL-999", "999 번");
        givenPrediction("BL-370", readyAt.plusSeconds(300));
        givenPrediction("BL-721", readyAt.plusSeconds(300));
        givenPrediction("BL-999", readyAt.plusSeconds(180));

        assertThat(rerouteProposalService.request(userId, journey.journeyId(), 0))
                .as("'찾아봤지만 나은 것이 없다'는 정상 결과다")
                .isEmpty();
        assertThat(activeProposalCount()).isZero();
    }

    @Test
    @DisplayName("이미 지나간 환승 번호로 요청하면 거절한다")
    void passedTransferIsRejected() {
        Instant readyAt = Instant.now().plusSeconds(240);
        givenLiveTransfer(0, readyAt);
        // 사용자가 이미 그 환승을 지나 다음 단계로 갔다.
        jdbcTemplate.update("update journey_session set current_leg_index = 99 where id = ?",
                journey.journeyId());

        assertThatThrownBy(() -> rerouteProposalService.request(userId, journey.journeyId(), 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        verifyNoInteractions(odSayApiPort);
    }

    @Test
    @DisplayName("환승 근거가 없으면 거절한다 — GPS 가 끊기면 문이 저절로 닫힌다")
    void unknownTransferIsRejected() {
        // 여정 시작 직후의 환승은 status=UNKNOWN, user_ready_at=null 이다.
        assertThatThrownBy(() -> rerouteProposalService.request(userId, journey.journeyId(), 0))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", JourneyErrorCode.REROUTE_NOT_ELIGIBLE);
        verifyNoInteractions(odSayApiPort);
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

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

    /**
     * 지금 타기로 돼 있는 노선의 식별자를 해석된 상태로 만든다.
     *
     * <p>실제로는 {@code JourneyTransitIdEnricher} 가 정적 노선표를 보고 채운다. 이 시험 DB 에는
     * 그 표가 없어 {@code route_id}·{@code boarding_seq} 가 비어 있고, 그러면 <b>기준선을 잴 수
     * 없어</b> 이 칸이 빈손을 낸다 — 재탐색이 아니라 준비물의 문제다.
     */
    private void givenResolvedBoarding(long waitLegId, String routeId) {
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = 'ST-G', source_start_stop_id = 'OTP-BOARD'
                 where id = ?
                """, waitLegId);
        // PR4c: alight 는 승차 leg(대기 leg 다음)의 도착 정류장이다. rideSec 조회 가드를 통과하게 채운다.
        jdbcTemplate.update("""
                update journey_leg set source_end_stop_id = 'OTP-ALIGHT'
                 where journey_id = (select journey_id from journey_leg where id = ?)
                   and route_revision = (select route_revision from journey_leg where id = ?)
                   and leg_index = (select leg_index from journey_leg where id = ?) + 1
                """, waitLegId, waitLegId, waitLegId);
        // baseline option 도 OTP route gtfsId(source_route_id)를 채운다 — 공정성 게이트는 기준선·후보
        // 둘 다 OTP 소요가 있어야 승격하므로 baseline 에도 열쇠가 있어야 한다.
        jdbcTemplate.update("""
                update journey_leg_route_option
                   set route_id = ?, source_route_id = ?, direction_key = 'UP',
                       boarding_seq = 3, alighting_seq = 9
                 where journey_leg_id = ? and option_index = 0
                """, routeId, routeId, waitLegId);
    }

    /** 같은 대기 단계에 다른 버스를 하나 더 심는다. 검색이 lane 을 여럿 준 상황이다. */
    private void givenAlternativeRoute(long waitLegId, String routeId, String routeName) {
        jdbcTemplate.update("""
                insert into journey_leg_route_option
                    (created_at, updated_at, journey_leg_id, option_index, lane_index,
                     route_id, source_route_id, route_name, direction_key, boarding_seq,
                     alighting_seq)
                values (now(), now(), ?, 1, 1, ?, ?, ?, 'UP', 3, 9)
                """, waitLegId, routeId, routeId, routeName);
    }

    private void givenPrediction(String routeId, Instant arrivalAt) {
        given(predictTransferUseCase.predict(anyString(), org.mockito.ArgumentMatchers.eq(routeId),
                any(), any(), any(), anyLong()))
                .willAnswer(call -> TransferPredictionResult.available(
                        call.getArgument(0), routeId, call.getArgument(2),
                        TransferArrival.Source.REALTIME, Instant.now(), call.getArgument(3),
                        arrivalAt, null, null));
    }

    /** 첫 환승이 가리키는 대기 단계. 노선 후보는 그 단계가 소유한다. */
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

    private int transferCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from transfer_connection
                 where journey_id = ? and deleted_at is null
                """, Integer.class, journey.journeyId());
    }

    private int activeProposalCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from reroute_proposal
                 where journey_id = ? and status = 'ACTIVE'
                """, Integer.class, journey.journeyId());
    }

    /** 버스 셋, 환승 둘. 두 번째 대기 단계가 "이후 환승"으로 남는다. */
    private PathResult twoTransferPath() {
        return new PathResult(1,
                new PathInfo(60, 4000, 900, 2, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장P")
                                .busStops("ST-G", "ST-P")
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build(),
                        SubPathFixture.walk(2).names("정류장P", "정류장Q").distance(100.0).build(),
                        SubPathFixture.bus(12).names("정류장Q", "정류장Z")
                                .busStops("ST-Q", "ST-Z")
                                .lanes(SubPathFixture.busLane("802", "BL-802")).build()),
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
