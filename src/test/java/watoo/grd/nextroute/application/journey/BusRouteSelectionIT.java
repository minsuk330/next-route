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
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyEventOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyNavigationGeometryUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.BusRouteSelectionService;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.route.service.TransferStopResolver;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 탄 버스 노선 선택(#598)이 <b>실제 여정 위에서</b> 도는지.
 *
 * <p>여정: 버스(370·371 묶음) → 도보 → 버스(721·140 묶음). 단계는 0 대기, 1 탑승, 2 도보, 3 대기, 4 탑승.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("탄 버스 노선 선택")
class BusRouteSelectionIT {

    private static final String SESSION_ID = "sess-bus-route-selection-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired BusRouteSelectionService selectionService;
    @Autowired GetJourneyNavigationGeometryUseCase geometryUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean TransferStopResolver transferStopResolver;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, mergedBusPath(), Instant.now().plusSeconds(600))));
        given(transferStopResolver.resolveStop(anyString())).willAnswer(call ->
                Optional.of(new TransferStopResolver.StopResolution(call.getArgument(0))));
        given(transferStopResolver.resolveRoute(anyString(), any())).willAnswer(call ->
                Optional.of(new TransferStopResolver.RouteResolution(
                        call.getArgument(0), call.getArgument(1), false, null)));
        given(transferStopResolver.resolveSeq(anyString(), anyString()))
                .willReturn(new TransferStopResolver.SeqResolution(List.of(3)));
        userId = newUser();
        journey = startJourneyUseCase.start(userId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("전제: 묶인 노선이 후보로 저장되고 첫 후보가 선택돼 있다")
    void startsWithFirstOption() {
        assertThat(journey.legs()).extracting(JourneyLegView::type).containsExactly(
                JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS, JourneyLegType.TRANSFER_WALK,
                JourneyLegType.WAIT_BUS, JourneyLegType.RIDE_BUS);
        assertThat(journey.legs().get(3).routeOptions()).extracting(JourneyRouteOptionView::routeName)
                .containsExactly("721", "140");
        assertThat(journey.legs().get(3).selectedRouteOptionId()).isEqualTo(option(3, 0).optionId());
        assertThat(journey.legs().get(4).selectedRouteOptionId()).isEqualTo(option(3, 0).optionId());
        // #614 선택 주인: 서버 기본. 선택 없는 도보 단계는 null
        assertThat(journey.legs()).extracting(JourneyLegView::routeOptionSelectedBy)
                .containsExactly("DEFAULT", "DEFAULT", null, "DEFAULT", "DEFAULT");
    }

    @Test
    @DisplayName("고르면 대기·탑승 단계의 선택 노선과 노선 id 가 바뀐다 — 개정본과 명령 번호는 그대로, 평가 번호만 오른다")
    void selectionSwitchesBothLegs() {
        JourneyRouteOptionView chosen = option(3, 1);

        JourneySnapshot after = selectionService.select(userId, journey.journeyId(),
                chosen.optionId(), journey.routeRevision(), 4);

        assertThat(after.legs().get(3).selectedRouteOptionId()).isEqualTo(chosen.optionId());
        assertThat(after.legs().get(4).selectedRouteOptionId()).isEqualTo(chosen.optionId());
        assertThat(chosen.routeId()).as("전제: 후보마다 노선 id 가 풀려 있다").isEqualTo("BL-140");
        assertThat(after.legs().get(3).routeId()).isEqualTo("BL-140");
        assertThat(after.legs().get(4).routeId()).isEqualTo("BL-140");
        // 다른 구간은 건드리지 않는다
        assertThat(after.legs().get(0).selectedRouteOptionId()).isEqualTo(option(0, 0).optionId());
        assertThat(after.routeRevision()).isEqualTo(journey.routeRevision());
        assertThat(after.commandVersion()).isEqualTo(journey.commandVersion());
        assertThat(after.evaluationVersion()).isEqualTo(journey.evaluationVersion() + 1);
        assertThat(after.legs()).hasSameSizeAs(journey.legs());
        assertThat(after.legs().get(3).routeOptionSelectedBy()).isEqualTo("USER");
        assertThat(after.legs().get(4).routeOptionSelectedBy()).isEqualTo("USER");
        assertThat(after.legs().get(0).routeOptionSelectedBy()).isEqualTo("DEFAULT");
    }

    @Test
    @DisplayName("#614 서버 기본과 같은 노선을 보내면 선택은 그대로 주인만 USER — 평가 번호 +1, 명령 번호·예측 시각은 그대로. 다시 보내면 무변화")
    void confirmingDefaultOptionMarksUser() {
        Long defaultOption = option(3, 0).optionId();

        JourneySnapshot confirmed = selectionService.select(userId, journey.journeyId(), defaultOption,
                journey.routeRevision(), 4);

        assertThat(confirmed.legs().get(3).selectedRouteOptionId()).isEqualTo(defaultOption);
        assertThat(confirmed.legs().get(3).routeOptionSelectedBy()).isEqualTo("USER");
        assertThat(confirmed.legs().get(4).routeOptionSelectedBy()).isEqualTo("USER");
        assertThat(confirmed.evaluationVersion()).isEqualTo(journey.evaluationVersion() + 1);
        assertThat(confirmed.commandVersion()).isEqualTo(journey.commandVersion());

        JourneySnapshot again = selectionService.select(userId, journey.journeyId(), defaultOption,
                journey.routeRevision(), 4);
        assertThat(again.evaluationVersion()).isEqualTo(confirmed.evaluationVersion());
    }

    @Test
    @DisplayName("옛 노선으로 잰 대기 예측은 비우고, 탑승 단계의 진행 관측은 남긴다")
    void clearsOldRoutePredictionButKeepsRideProgress() {
        jdbcTemplate.update("""
                update journey_leg set predicted_end_at = now() + interval '5 minutes',
                       evidence_source = 'REALTIME_ARRIVAL', evidence_observed_at = now()
                 where journey_id = ? and leg_index = 3
                """, journey.journeyId());
        jdbcTemplate.update("""
                update journey_leg set remaining_stops = 4, evidence_source = 'CLIENT_GEOMETRY',
                       evidence_observed_at = now(), progress_section_id = 'sec-9',
                       predicted_end_at = now() + interval '20 minutes',
                       vehicle_id = 'V-1', vehicle_id_type = 'VEH_ID', vehicle_source = 'AUTO_MATCH'
                 where journey_id = ? and leg_index = 4
                """, journey.journeyId());

        selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(), journey.routeRevision(), 4);

        Map<String, Object> wait = leg(3);
        assertThat(wait.get("predicted_end_at")).isNull();
        assertThat(wait.get("evidence_source")).isNull();
        assertThat(wait.get("evidence_observed_at")).isNull();
        Map<String, Object> ride = leg(4);
        assertThat(ride.get("remaining_stops")).isEqualTo(4);
        assertThat(ride.get("evidence_source")).isEqualTo("CLIENT_GEOMETRY");
        assertThat(ride.get("evidence_observed_at")).isNotNull();
        assertThat(ride.get("progress_section_id")).isEqualTo("sec-9");
        assertThat(ride.get("predicted_end_at")).isNull();
        // 워커가 쓰는 차량 칼럼은 건드리지 않는다(V61 CHECK 가 셋을 묶는다)
        assertThat(ride.get("vehicle_id")).isEqualTo("V-1");
    }

    @Test
    @DisplayName("평가를 지금으로 당긴다 — 돌고 있던 평가(선점 표시)는 저장 때 소유를 잃는다")
    void pullsEvaluationForwardAndInvalidatesInFlightClaim() {
        Instant lease = Instant.now().plusSeconds(120);
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = ?, evaluation_claimed_until = ?, evaluation_urgent = false
                 where id = ?
                """, Timestamp.from(lease), Timestamp.from(lease), journey.journeyId());

        selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(), journey.routeRevision(), 4);

        Map<String, Object> session = jdbcTemplate.queryForMap(
                "select next_evaluation_at, evaluation_urgent from journey_session where id = ?", journey.journeyId());
        Instant next = ((Timestamp) session.get("next_evaluation_at")).toInstant();
        assertThat(next).as("선점한 평가가 저장 조건으로 보는 값이 바뀌었다").isNotEqualTo(lease);
        assertThat(next).isCloseTo(Instant.now(), within(10, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(session.get("evaluation_urgent")).isEqualTo(true);
    }

    @Test
    @DisplayName("같은 후보를 다시 보내면 아무것도 바뀌지 않는다")
    void sameOptionIsNoOp() {
        Long chosen = option(3, 1).optionId();
        JourneySnapshot first = selectionService.select(userId, journey.journeyId(), chosen, journey.routeRevision(), 4);
        jdbcTemplate.update("update journey_session set evaluation_urgent = false where id = ?", journey.journeyId());

        JourneySnapshot second = selectionService.select(userId, journey.journeyId(), chosen, journey.routeRevision(), 4);

        assertThat(second.evaluationVersion()).isEqualTo(first.evaluationVersion());
        assertThat(second.commandVersion()).isEqualTo(first.commandVersion());
        assertThat(jdbcTemplate.queryForObject("select evaluation_urgent from journey_session where id = ?",
                Boolean.class, journey.journeyId())).isFalse();
    }

    @Test
    @DisplayName("#614 PR-2 서버 기본과 같은 노선 확인(DEFAULT→USER)도 평가를 당긴다 — 서버 기본 기준으로 노선을 옮기던 평가는 저장 때 버려진다")
    void confirmingDefaultInvalidatesInFlightClaim() {
        Instant lease = Instant.now().plusSeconds(120);
        jdbcTemplate.update("""
                update journey_session set next_evaluation_at = ?, evaluation_claimed_until = ?, evaluation_urgent = false
                 where id = ?
                """, Timestamp.from(lease), Timestamp.from(lease), journey.journeyId());

        selectionService.select(userId, journey.journeyId(), option(3, 0).optionId(), journey.routeRevision(), 4);

        Instant next = jdbcTemplate.queryForObject("select next_evaluation_at from journey_session where id = ?",
                Timestamp.class, journey.journeyId()).toInstant();
        assertThat(next).isNotEqualTo(lease);
        assertThat(next).isCloseTo(Instant.now(), within(10, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("선택 전에 읽은 명령 번호로 보낸 탑승 전이가 그대로 반영된다 — 선택은 명령 번호를 올리지 않는다")
    void boardingTransitionWithPreSelectionVersionStillApplies() {
        // legIndex 생략: 지금 버스를 기다리는 중(0)이라 바로 다음 탑승 구간(1)이 대상이다
        JourneySnapshot selected = selectionService.select(userId, journey.journeyId(),
                option(0, 1).optionId(), journey.routeRevision(), null);
        assertThat(selected.legs().get(1).routeId()).isEqualTo("BL-371");

        JourneyEventOutcome boarded = applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        journey.commandVersion(), journey.routeRevision(), 0, 1, Instant.now()));

        assertThat(boarded.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(boarded.journey().currentLegIndex()).isEqualTo(1);
        assertThat(boarded.journey().currentLeg().routeId()).isEqualTo("BL-371");
    }

    @Test
    @DisplayName("탑승 중에도 다시 고를 수 있다(legIndex 생략 = 타고 있는 구간). 내린 뒤에는 거절한다")
    void reselectWhileRidingThenRejectAfterAlighting() {
        transition(journey.commandVersion(), 0, 1);

        JourneySnapshot riding = selectionService.select(userId, journey.journeyId(),
                option(0, 1).optionId(), journey.routeRevision(), null);
        assertThat(riding.legs().get(1).routeId()).isEqualTo("BL-371");
        assertThat(riding.legs().get(0).selectedRouteOptionId()).isEqualTo(option(0, 1).optionId());

        transition(riding.commandVersion(), 1, 2);
        assertError(() -> selectionService.select(userId, journey.journeyId(),
                option(0, 0).optionId(), journey.routeRevision(), 1), JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
        // 걷는 중이면 legIndex 없이 고를 대상이 없다
        assertError(() -> selectionService.select(userId, journey.journeyId(),
                option(3, 1).optionId(), journey.routeRevision(), null), JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
    }

    @Test
    @DisplayName("거절: 다른 구간의 후보 422, 버스 탑승 구간이 아닌 순번 422, 낡은 개정본 409, 남의 여정 404, 끝난 여정 409")
    void rejections() {
        Long otherLegOption = option(0, 1).optionId();
        assertError(() -> selectionService.select(userId, journey.journeyId(), otherLegOption,
                journey.routeRevision(), 4), JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND);
        assertError(() -> selectionService.select(userId, journey.journeyId(), 999_999_999L,
                journey.routeRevision(), 4), JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND);
        assertError(() -> selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(),
                journey.routeRevision(), 3), JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
        assertError(() -> selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(),
                journey.routeRevision(), 2), JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
        assertThatThrownBy(() -> selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(),
                journey.routeRevision() + 1, 4)).isInstanceOf(StaleRouteRevisionException.class);
        // 지워진 후보는 고를 수 없다
        jdbcTemplate.update("update journey_leg_route_option set deleted_at = now() where id = ?",
                option(0, 1).optionId());
        assertError(() -> selectionService.select(userId, journey.journeyId(), option(0, 1).optionId(),
                journey.routeRevision(), 1), JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND);
        Long stranger = newUser();
        assertError(() -> selectionService.select(stranger, journey.journeyId(), option(3, 1).optionId(),
                journey.routeRevision(), 4), JourneyErrorCode.JOURNEY_NOT_FOUND);
        // 거절은 아무것도 바꾸지 않았다
        assertThat(leg(3).get("selected_route_option_id")).isEqualTo(option(3, 0).optionId());

        applyJourneyEventUseCase.apply(userId, journey.journeyId(), new JourneyCommand(UUID.randomUUID(),
                JourneyEventType.JOURNEY_CANCELED, journey.commandVersion(), journey.routeRevision(),
                null, null, Instant.now()));
        assertError(() -> selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(),
                journey.routeRevision(), 4), JourneyErrorCode.JOURNEY_NOT_ACTIVE);
    }

    @Test
    @DisplayName("지도 좌표의 버전 키: 첫 후보면 그대로, 다른 후보를 고르면 바뀐다 — 앱이 캐시를 버리고 다시 받는다")
    void geometryVersionKeyFollowsSelection() {
        String before = geometryUseCase.get(userId, journey.journeyId(), null).geometryVersion();

        selectionService.select(userId, journey.journeyId(), option(3, 1).optionId(), journey.routeRevision(), 4);
        String selected = geometryUseCase.get(userId, journey.journeyId(), null).geometryVersion();
        assertThat(selected).isEqualTo(before + ":b3.1");

        selectionService.select(userId, journey.journeyId(), option(3, 0).optionId(), journey.routeRevision(), 4);
        assertThat(geometryUseCase.get(userId, journey.journeyId(), null).geometryVersion()).isEqualTo(before);
    }

    private void transition(long commandVersion, int from, int to) {
        JourneyEventOutcome outcome = applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                new JourneyCommand(UUID.randomUUID(), JourneyEventType.CLIENT_LEG_TRANSITION_DETECTED,
                        commandVersion, journey.routeRevision(), from, to, Instant.now()));
        assertThat(outcome.result()).isEqualTo(JourneyEventResult.APPLIED);
    }

    private JourneyRouteOptionView option(int waitLegIndex, int optionIndex) {
        return journey.legs().get(waitLegIndex).routeOptions().stream()
                .filter(option -> option.optionIndex() == optionIndex)
                .findFirst().orElseThrow();
    }

    private Map<String, Object> leg(int legIndex) {
        return jdbcTemplate.queryForMap("""
                select * from journey_leg where journey_id = ? and route_revision = ? and leg_index = ?
                """, journey.journeyId(), journey.routeRevision(), legIndex);
    }

    private static void assertError(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, JourneyErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(code));
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
        for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event",
                "journey_notification")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    /** 버스 둘, 환승 하나. 두 버스 구간 모두 노선 두 개가 묶여 있다. */
    private PathResult mergedBusPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370"),
                                        SubPathFixture.busLane("371", "BL-371")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721"),
                                        SubPathFixture.busLane("140", "BL-140")).build()),
                List.of());
    }
}
