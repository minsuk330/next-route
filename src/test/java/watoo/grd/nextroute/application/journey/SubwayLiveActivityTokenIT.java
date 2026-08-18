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
import watoo.grd.nextroute.application.journey.port.in.ManageLiveActivityTokenUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;
import watoo.grd.nextroute.global.error.BusinessException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * Live Activity 수신 주소 등록·해제의 계약.
 *
 * <p>여기서 막는 것 —
 * <ul>
 *   <li>토큰이 회전할 때마다 새 행이 쌓이거나, 반대로 재등록이 막히는 것</li>
 *   <li>남의 여정·남의 토큰을 건드리는 것</li>
 *   <li>기기 주소 없이 Activity 주소만 받아 <b>조용히 못 보내는 행</b>이 쌓이는 것</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("Live Activity 토큰 등록·해제 계약")
class SubwayLiveActivityTokenIT {

    private static final String SESSION_ID = "sess-subway-la-token-it";
    private static final String PATH_KEY = "0";
    private static final String DEVICE_TOKEN = "device-token-la-it";
    private static final String ACTIVITY_TOKEN = "activity-token-la-it";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ManageLiveActivityTokenUseCase useCase;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        insertDeviceToken(userId, DEVICE_TOKEN);
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(id -> jdbcTemplate.update("""
                delete from subway_live_activity_token where user_id = ?
                """, id));
        createdUserIds.forEach(id -> jdbcTemplate.update(
                "delete from user_push_token where user_id = ?", id));
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("등록하면 기기 주소와 이어진다 — 발송에 두 토큰이 다 필요하다")
    void registrationLinksTheDevice() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);

        Long linked = jdbcTemplate.queryForObject("""
                select push_token_id from subway_live_activity_token where activity_token = ?
                """, Long.class, ACTIVITY_TOKEN);
        assertThat(linked).isEqualTo(deviceTokenId());
    }

    @Test
    @DisplayName("토큰이 회전해 다시 등록해도 행이 늘지 않는다")
    void reRegistrationIsUpsert() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.DEVELOPMENT);

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                select environment from subway_live_activity_token where activity_token = ?
                """, String.class, ACTIVITY_TOKEN)).isEqualTo("DEVELOPMENT");
    }

    @Test
    @DisplayName("해제한 토큰을 다시 등록할 수 있다 — 통 unique 면 영영 막힌다")
    void releasedTokenCanComeBack() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);
        useCase.unregister(userId, journeyId, ACTIVITY_TOKEN);

        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);

        assertThat(liveRowCount()).isEqualTo(1);
        assertThat(rowCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("provider 가 껐던 토큰도 재등록으로 되살아난다")
    void reRegistrationRevivesADisabledToken() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);
        jdbcTemplate.update("""
                update subway_live_activity_token set enabled = false where activity_token = ?
                """, ACTIVITY_TOKEN);

        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);

        assertThat(jdbcTemplate.queryForObject("""
                select enabled from subway_live_activity_token where activity_token = ?
                """, Boolean.class, ACTIVITY_TOKEN)).isTrue();
    }

    @Test
    @DisplayName("같은 Activity 가 새 여정으로 오면 여정을 옮겨 붙인다")
    void reRegistrationRebindsTheJourney() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);
        jdbcTemplate.update("""
                update journey_session set status = 'COMPLETED', ended_at = now() where id = ?
                """, journeyId);
        JourneySnapshot second = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));

        useCase.register(userId, second.journeyId(), ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);

        assertThat(jdbcTemplate.queryForObject("""
                select journey_id from subway_live_activity_token where activity_token = ?
                """, Long.class, ACTIVITY_TOKEN)).isEqualTo(second.journeyId());
        assertThat(liveRowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("기기 주소가 없으면 400 이다 — 조용히 못 보내는 행을 만들지 않는다")
    void registrationRequiresTheDeviceToken() {
        assertThatThrownBy(() -> useCase.register(userId, journeyId, ACTIVITY_TOKEN,
                "not-registered-device", PushEnvironment.PRODUCTION))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("푸시 토큰");

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("남의 여정에는 등록할 수 없다")
    void cannotRegisterOnSomeoneElsesJourney() {
        Long stranger = newUser();

        assertThatThrownBy(() -> useCase.register(stranger, journeyId, ACTIVITY_TOKEN,
                DEVICE_TOKEN, PushEnvironment.PRODUCTION))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("없는 토큰을 해제해도 조용히 성공한다 — 404 는 존재 여부를 흘린다")
    void unregisteringAnUnknownTokenSucceeds() {
        useCase.unregister(userId, journeyId, "never-registered");

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("끝난 여정의 주소도 해제할 수 있다 — 정리는 그 API 의 정상 사용이다")
    void unregisterWorksOnEndedJourneys() {
        useCase.register(userId, journeyId, ACTIVITY_TOKEN, DEVICE_TOKEN,
                PushEnvironment.PRODUCTION);
        jdbcTemplate.update("""
                update journey_session set status = 'COMPLETED', ended_at = now() where id = ?
                """, journeyId);

        useCase.unregister(userId, journeyId, ACTIVITY_TOKEN);

        assertThat(liveRowCount()).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private Integer rowCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from subway_live_activity_token where user_id = ?",
                Integer.class, userId);
    }

    private Integer liveRowCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from subway_live_activity_token
                 where user_id = ? and deleted_at is null
                """, Integer.class, userId);
    }

    private Long deviceTokenId() {
        return jdbcTemplate.queryForObject(
                "select id from user_push_token where token = ?", Long.class, DEVICE_TOKEN);
    }

    private void insertDeviceToken(Long owner, String token) {
        jdbcTemplate.update("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment,
                     enabled, last_seen_at)
                values (now(), now(), ?, 'IOS', 'FCM', ?, 'PRODUCTION', true, now())
                """, owner, token);
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
        for (String table : List.of("subway_leg_binding", "journey_leg", "journey_revision",
                "transfer_connection", "journey_event")) {
            jdbcTemplate.update("delete from " + table
                    + " where journey_id in (select id from journey_session where user_id = ?)", id);
        }
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult subwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(15).names("승차역", "하차역")
                                .subwayStations(900222, 900224).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.walk(5).names("하차역", "목적지").distance(300.0).build()),
                List.of());
    }
}
