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
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.HeartbeatJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * heartbeat 가 lease 만 건드리는지 DB 까지 확인한다.
 *
 * <p>여기서 못박는 것은 세 가지다. lease 는 실제로 늘어난다, 쓰기는 60초에 한 번으로 합쳐진다,
 * 그리고 번호(commandVersion·evaluationVersion)는 올라가지 않는다. 마지막 항목이 특히 중요하다 —
 * heartbeat 로 {@code commandVersion} 이 오르면 앱이 보낸 정상 명령이 충돌로 거절된다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("heartbeat 는 lease 만 연장한다")
class JourneyHeartbeatIT {

    private static final String SESSION_ID = "sess-heartbeat-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired HeartbeatJourneyUseCase heartbeatJourneyUseCase;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
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
    @DisplayName("합치기 창을 넘긴 heartbeat 는 lease 를 실제로 연장한다")
    void extendsLeaseAfterCoalesceWindow() {
        Instant before = storedLeaseUntil();
        ageLastSignal();

        JourneySnapshot after = heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());

        assertThat(storedLeaseUntil()).isAfter(before);
        assertThat(after.guidanceLeaseUntil()).isEqualTo(storedLeaseUntil());
    }

    @Test
    @DisplayName("60초 안에 연달아 온 heartbeat 는 lease 를 다시 쓰지 않는다")
    void coalescesWrites() {
        ageLastSignal();
        heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());
        Instant extended = storedLeaseUntil();

        heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());
        heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());

        assertThat(storedLeaseUntil()).isEqualTo(extended);
    }

    @Test
    @DisplayName("번호는 올라가지 않는다 — 앱이 든 명령 번호가 낡지 않아야 한다")
    void doesNotBumpVersions() {
        ageLastSignal();

        JourneySnapshot after = heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());

        assertThat(after.commandVersion()).isEqualTo(journey.commandVersion());
        assertThat(after.evaluationVersion()).isEqualTo(journey.evaluationVersion());
    }

    @Test
    @DisplayName("응답에 예측은 없다. 실시간 조회를 이 경로에서 하지 않는다")
    void returnsPlainSnapshot() {
        JourneySnapshot after = heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId());

        assertThat(after.eta()).isNull();
        assertThat(after.legs()).isNotEmpty();
        assertThat(after.serverTime()).isNotNull();
    }

    @Test
    @DisplayName("끝난 여정은 heartbeat 로 되살아나지 않는다")
    void endedJourneyIsNotRevived() {
        jdbcTemplate.update("""
                update journey_session
                set status = 'EXPIRED', end_reason = 'CLIENT_LEASE_EXPIRED', ended_at = now()
                where id = ?
                """, journey.journeyId());

        assertThatThrownBy(() -> heartbeatJourneyUseCase.heartbeat(userId, journey.journeyId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_NOT_ACTIVE));
    }

    @Test
    @DisplayName("남의 여정은 없는 여정과 똑같이 취급한다")
    void otherUsersJourneyHidden() {
        Long stranger = newUser();

        assertThatThrownBy(() -> heartbeatJourneyUseCase.heartbeat(stranger, journey.journeyId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    /**
     * 마지막 신호를 과거로 밀어 "합치기 창이 지난 상태"를 만든다.
     *
     * <p>여정을 시작하면 {@code lastHeartbeatAt} 이 시작 시각으로 채워지므로, 시작 직후의
     * heartbeat 는 합치기 규칙에 걸려 아무것도 쓰지 않는다. 시계를 흔들지 않고 그 반대 상황을
     * 만들려면 마지막 신호 시각을 직접 뒤로 옮기는 것이 가장 단순하다.
     */
    private void ageLastSignal() {
        jdbcTemplate.update("""
                update journey_session
                set last_heartbeat_at = now() - interval '90 seconds'
                where id = ?
                """, journey.journeyId());
    }

    private Instant storedLeaseUntil() {
        return jdbcTemplate.queryForObject(
                "select guidance_lease_until from journey_session where id = ?",
                Instant.class, journey.journeyId());
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

    private PathResult busPath() {
        return new PathResult(1,
                new PathInfo(25, 2000, 500, 1, "정류장A", "정류장F", null),
                List.of(
                        SubPathFixture.bus(20).names("정류장A", "정류장F")
                                .busStops("ST-A", "ST-F")
                                .lanes(SubPathFixture.busLane("370", "BL-370")).build(),
                        SubPathFixture.walk(3).names("정류장F", "목적지").distance(150.0).build()),
                List.of());
    }
}
