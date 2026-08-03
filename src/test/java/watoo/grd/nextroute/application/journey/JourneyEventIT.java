package watoo.grd.nextroute.application.journey;

import jakarta.persistence.EntityManager;
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
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * 명령이 실제 DB 에 반영되는 과정을 확인한다.
 *
 * <p>전이 규칙 자체는 {@code JourneyStateMachineTest} 가 DB 없이 본다. 여기서 보는 것은 그
 * 결정이 <b>여러 표에 제대로 옮겨지는가</b>와 <b>재전송이 두 번 반영되지 않는가</b>다. 둘 다
 * 저장을 거쳐야만 드러난다.
 *
 * <p>트랜잭션을 걸지 않는다. 명령 처리는 스스로 트랜잭션을 열고, 커밋된 결과를 다음 명령이
 * 봐야 하기 때문이다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
class JourneyEventIT {

    private static final String SESSION_ID = "sess-event-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyEventUseCase applyJourneyEventUseCase;
    @Autowired GetActiveJourneyUseCase getActiveJourneyUseCase;
    @Autowired GetJourneyUseCase getJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired EntityManager entityManager;

    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, twoBusPath(), Instant.now().plusSeconds(600))));
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

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id
                """, Long.class);
        createdUserIds.add(id);
        return id;
    }

    private void deleteJourneysOf(Long userId) {
        jdbcTemplate.update("""
                update journey_leg set selected_route_option_id = null
                where journey_id in (select id from journey_session where user_id = ?)
                """, userId);
        jdbcTemplate.update("""
                delete from journey_leg_route_option
                where journey_leg_id in (
                    select l.id from journey_leg l
                    join journey_session s on s.id = l.journey_id
                    where s.user_id = ?)
                """, userId);
        for (String table : List.of("journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update(
                    "delete from " + table
                            + " where journey_id in (select id from journey_session where user_id = ?)",
                    userId);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", userId);
    }

    /** 버스 → 도보 → 버스. 두 번째 구간은 노선 후보가 둘이다. */
    private PathResult twoBusPath() {
        return new PathResult(1,
                new PathInfo(40, 2500, 600, 1, "정류장A", "정류장Z", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "정류장G").distance(150.0).build(),
                        SubPathFixture.bus(15).names("정류장G", "정류장Z")
                                .busStops("ST-G", "ST-Z")
                                .lanes(SubPathFixture.busLane("721", "BL-721"),
                                        SubPathFixture.busLane("140", "BL-140")).build()),
                List.of());
    }

    private JourneyEventOutcome send(JourneyEventType type, long baseCommandVersion) {
        return send(UUID.randomUUID(), type, baseCommandVersion, journey.routeRevision(), null);
    }

    private JourneyEventOutcome send(UUID eventId, JourneyEventType type, long baseCommandVersion,
                                     int routeRevision, Long routeOptionId) {
        return applyJourneyEventUseCase.apply(userId, journey.journeyId(),
                new JourneyCommand(eventId, type, baseCommandVersion, routeRevision, routeOptionId));
    }

    private String eventResultOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "select result from journey_event where event_id = ?", String.class, eventId);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from journey_event where journey_id = ?",
                Integer.class, journey.journeyId());
    }

    // ── 반영 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("탑승을 반영하면 단계와 명령 번호가 함께 움직인다")
    void boarding_appliesToLegsAndVersion() {
        JourneyEventOutcome outcome = send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.APPLIED);
        JourneySnapshot after = outcome.journey();
        assertThat(after.commandVersion()).isEqualTo(2L);
        assertThat(after.currentLegIndex()).isEqualTo(1);
        assertThat(after.legs().get(0).state()).isEqualTo(JourneyLegState.COMPLETED);
        assertThat(after.legs().get(1).state()).isEqualTo(JourneyLegState.ACTIVE);
    }

    @Test
    @DisplayName("탑승 → 하차 → 탑승으로 환승까지 진행된다")
    void fullProgress() {
        send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L);
        send(JourneyEventType.USER_CONFIRMED_ALIGHTING, 2L);
        JourneySnapshot after = send(JourneyEventType.USER_CONFIRMED_BOARDING, 3L).journey();

        assertThat(after.commandVersion()).isEqualTo(4L);
        assertThat(after.currentLegIndex())
                .as("환승 도보를 지나 두 번째 버스에 탄 상태")
                .isEqualTo(4);
        assertThat(after.legs()).extracting(JourneyLegView::state).containsExactly(
                JourneyLegState.COMPLETED,   // WAIT_BUS
                JourneyLegState.COMPLETED,   // RIDE_BUS
                JourneyLegState.COMPLETED,   // TRANSFER_WALK — 자동 완료
                JourneyLegState.COMPLETED,   // WAIT_BUS
                JourneyLegState.ACTIVE);     // RIDE_BUS
    }

    @Test
    @DisplayName("차량을 고르면 기다리기와 타고가기가 같은 노선을 가리킨다")
    void selectVehicle_appliesToBothLegs() {
        send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L);
        send(JourneyEventType.USER_CONFIRMED_ALIGHTING, 2L);
        JourneySnapshot beforeSelect = getActiveJourneyUseCase.getActive(userId).orElseThrow();
        Long optionId = beforeSelect.legs().get(3).routeOptions().get(1).optionId();

        JourneySnapshot after = send(UUID.randomUUID(), JourneyEventType.USER_SELECTED_VEHICLE,
                beforeSelect.commandVersion(), beforeSelect.routeRevision(), optionId).journey();

        assertThat(after.legs().get(3).selectedRouteOptionId()).isEqualTo(optionId);
        assertThat(after.legs().get(4).selectedRouteOptionId()).isEqualTo(optionId);
    }

    @Test
    @DisplayName("취소하면 여정이 끝나고 더는 명령을 받지 않는다")
    void cancel_endsJourney() {
        JourneySnapshot after = send(JourneyEventType.JOURNEY_CANCELED, 1L).journey();

        assertThat(after.status()).isEqualTo(JourneyStatus.CANCELED);
        assertThat(jdbcTemplate.queryForObject(
                "select end_reason from journey_session where id = ?",
                String.class, journey.journeyId())).isEqualTo("USER_CANCELED");
        assertThat(getActiveJourneyUseCase.getActive(userId)).isEmpty();

        JourneyEventOutcome afterEnd = send(JourneyEventType.USER_CONFIRMED_BOARDING, 2L);
        assertThat(afterEnd.result()).isEqualTo(JourneyEventResult.REJECTED);
    }

    @Test
    @DisplayName("영속성 컨텍스트를 비워도 반영 결과가 남아 있다")
    void survivesDetach() {
        send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L);

        entityManager.clear();

        JourneySnapshot reloaded = getActiveJourneyUseCase.getActive(userId).orElseThrow();
        assertThat(reloaded.commandVersion()).isEqualTo(2L);
        assertThat(reloaded.legs().get(1).state()).isEqualTo(JourneyLegState.ACTIVE);
    }

    // ── 재전송 ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 명령이 다시 오면 두 번 반영하지 않는다")
    void resend_notAppliedTwice() {
        UUID eventId = UUID.randomUUID();
        JourneyEventOutcome first = send(eventId, JourneyEventType.USER_CONFIRMED_BOARDING,
                1L, journey.routeRevision(), null);

        JourneyEventOutcome second = send(eventId, JourneyEventType.USER_CONFIRMED_BOARDING,
                1L, journey.routeRevision(), null);

        assertThat(first.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(second.result())
                .as("앱이 응답을 못 받아 다시 보내는 것은 정상 동작이다")
                .isEqualTo(JourneyEventResult.DUPLICATE);
        assertThat(second.journey().commandVersion())
                .as("두 번째 요청이 번호를 또 올리면 안 된다")
                .isEqualTo(first.journey().commandVersion());
        assertThat(eventCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("재전송에도 최신 상태를 함께 준다")
    void resend_carriesCurrentState() {
        UUID eventId = UUID.randomUUID();
        send(eventId, JourneyEventType.USER_CONFIRMED_BOARDING, 1L, journey.routeRevision(), null);

        JourneyEventOutcome second = send(eventId, JourneyEventType.USER_CONFIRMED_BOARDING,
                1L, journey.routeRevision(), null);

        assertThat(second.journey().legs().get(1).state()).isEqualTo(JourneyLegState.ACTIVE);
    }

    // ── 충돌·거절 ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("낡은 명령 번호로 온 명령은 반영하지 않고 이력에 충돌로 남는다")
    void staleVersion_conflictRecorded() {
        send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L);

        UUID eventId = UUID.randomUUID();
        JourneyEventOutcome outcome = send(eventId, JourneyEventType.USER_CONFIRMED_ALIGHTING,
                1L, journey.routeRevision(), null);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(outcome.journey().commandVersion())
                .as("충돌이어도 최신 상태를 줘야 앱이 다시 그린다")
                .isEqualTo(2L);
        assertThat(eventResultOf(eventId)).isEqualTo("CONFLICT");
    }

    @Test
    @DisplayName("규칙상 할 수 없는 명령은 거절로 남고 상태를 바꾸지 않는다")
    void rejected_doesNotChangeState() {
        UUID eventId = UUID.randomUUID();
        JourneyEventOutcome outcome = send(eventId, JourneyEventType.USER_CONFIRMED_ALIGHTING,
                1L, journey.routeRevision(), null);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.REJECTED);
        assertThat(outcome.journey().commandVersion()).isEqualTo(1L);
        assertThat(outcome.journey().currentLegIndex()).isZero();
        assertThat(eventResultOf(eventId)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("이전 경로 개정본을 가리키는 명령은 반영하지 않는다")
    void staleRouteRevision_conflict() {
        JourneyEventOutcome outcome = send(UUID.randomUUID(),
                JourneyEventType.USER_CONFIRMED_BOARDING, 1L, 99, null);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.CONFLICT);
        assertThat(outcome.journey().currentLegIndex()).isZero();
    }

    @Test
    @DisplayName("상태를 바꾸지 않는 명령은 번호를 올리지 않는다")
    void appResumed_keepsVersion() {
        JourneyEventOutcome outcome = send(JourneyEventType.APP_RESUMED, 1L);

        assertThat(outcome.result()).isEqualTo(JourneyEventResult.APPLIED);
        assertThat(outcome.journey().commandVersion()).isEqualTo(1L);
    }


    // ── 조회와 종료 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("끝난 여정도 번호로는 볼 수 있다")
    void endedJourney_stillReadable() {
        send(JourneyEventType.JOURNEY_COMPLETED, 1L);

        assertThat(getActiveJourneyUseCase.getActive(userId))
                .as("진행 중인 것만 찾는 조회에는 안 나온다")
                .isEmpty();
        assertThat(getJourneyUseCase.get(userId, journey.journeyId()))
                .as("알림을 눌러 들어왔는데 이미 끝났을 수 있다")
                .isPresent()
                .get()
                .satisfies(j -> assertThat(j.status()).isEqualTo(JourneyStatus.COMPLETED));
    }

    @Test
    @DisplayName("도착으로 끝내면 이유가 취소와 다르다")
    void complete_recordsArrivalReason() {
        send(JourneyEventType.JOURNEY_COMPLETED, 1L);

        assertThat(jdbcTemplate.queryForObject(
                "select end_reason from journey_session where id = ?",
                String.class, journey.journeyId())).isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("남의 여정은 번호로도 안 보인다")
    void otherUsersJourney_notReadable() {
        Long other = newUser();

        assertThat(getJourneyUseCase.get(other, journey.journeyId())).isEmpty();
    }

    @Test
    @DisplayName("폴링 간격은 지금 단계에 따라 달라지고 끝나면 0 이 된다")
    void recommendedPollSeconds_followsCurrentLeg() {
        assertThat(journey.currentLeg().type()).isEqualTo(JourneyLegType.WAIT_BUS);
        assertThat(journey.recommendedPollSeconds())
                .as("기다리는 동안이 가장 자주 바뀐다")
                .isEqualTo(10);

        JourneySnapshot riding = send(JourneyEventType.USER_CONFIRMED_BOARDING, 1L).journey();
        assertThat(riding.recommendedPollSeconds()).isEqualTo(15);

        JourneySnapshot walking = send(JourneyEventType.USER_CONFIRMED_ALIGHTING, 2L).journey();
        assertThat(walking.currentLeg().type()).isEqualTo(JourneyLegType.TRANSFER_WALK);
        assertThat(walking.recommendedPollSeconds()).isEqualTo(30);

        JourneySnapshot ended = send(JourneyEventType.JOURNEY_CANCELED, 3L).journey();
        assertThat(ended.recommendedPollSeconds())
                .as("끝난 여정을 계속 물어볼 이유가 없다")
                .isZero();
    }

    // ── 소유권 ───────────────────────────────────────────────────────────────


    @Test
    @DisplayName("남의 여정에는 명령을 보낼 수 없다")
    void otherUsersJourney_notFound() {
        Long other = newUser();

        assertThatThrownBy(() -> applyJourneyEventUseCase.apply(other, journey.journeyId(),
                new JourneyCommand(UUID.randomUUID(),
                        JourneyEventType.USER_CONFIRMED_BOARDING, 1L, 1, null)))
                .isInstanceOf(NoSuchElementException.class);

        assertThat(getActiveJourneyUseCase.getActive(userId).orElseThrow().commandVersion())
                .isEqualTo(1L);
    }
}
