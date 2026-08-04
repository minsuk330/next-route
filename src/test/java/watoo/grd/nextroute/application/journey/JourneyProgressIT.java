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
import watoo.grd.nextroute.application.journey.dto.JourneyProgressCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome;
import watoo.grd.nextroute.application.journey.dto.JourneyProgressOutcome.Result;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.exception.ProgressTimestampConflictException;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyProgressUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

/**
 * progress 병합 규칙을 DB 까지 확인한다.
 *
 * <p>규칙의 핵심은 "같은 관측을 두 번 반영하지 않는다"이고, 그것이 지켜지는지는
 * {@code evaluation_version} 이 몇 번 올라갔는지로만 확인할 수 있다. 그래서 단위테스트가 아니라
 * 통합테스트로 둔다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("앱 progress 는 최신 관측일 때만 반영된다")
class JourneyProgressIT {

    private static final String SESSION_ID = "sess-progress-it";
    private static final String PATH_KEY = "0";
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-04T09:00:00Z");

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired ApplyJourneyProgressUseCase applyJourneyProgressUseCase;
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
    @DisplayName("첫 progress 는 반영되고 evaluationVersion 이 하나 올라간다")
    void firstProgressApplies() {
        long before = journey.evaluationVersion();

        JourneyProgressOutcome outcome = send(UUID.randomUUID(), OBSERVED_AT, 0.42);

        assertThat(outcome.result()).isEqualTo(Result.APPLIED);
        assertThat(outcome.journey().evaluationVersion()).isEqualTo(before + 1);
        assertThat(outcome.journey().commandVersion()).isEqualTo(journey.commandVersion());
    }

    @Test
    @DisplayName("같은 progressId 재전송은 evaluationVersion 을 두 번 올리지 않는다")
    void resendIsNoOp() {
        UUID progressId = UUID.randomUUID();
        long applied = send(progressId, OBSERVED_AT, 0.42).journey().evaluationVersion();

        JourneyProgressOutcome again = send(progressId, OBSERVED_AT, 0.42);

        assertThat(again.result()).isEqualTo(Result.DUPLICATE);
        assertThat(again.journey().evaluationVersion()).isEqualTo(applied);
    }

    @Test
    @DisplayName("더 오래된 관측은 상태를 바꾸지 않는다")
    void olderObservationIsStale() {
        send(UUID.randomUUID(), OBSERVED_AT, 0.42);
        long applied = currentEvaluationVersion();

        JourneyProgressOutcome older =
                send(UUID.randomUUID(), OBSERVED_AT.minusSeconds(30), 0.10);

        assertThat(older.result()).isEqualTo(Result.STALE);
        assertThat(older.journey().evaluationVersion()).isEqualTo(applied);
        assertThat(storedSegmentProgress()).isEqualByComparingTo(BigDecimal.valueOf(0.42));
    }

    @Test
    @DisplayName("같은 관측 시각에 다른 내용이 오면 충돌로 거절한다")
    void sameTimestampDifferentContentConflicts() {
        send(UUID.randomUUID(), OBSERVED_AT, 0.42);

        assertThatThrownBy(() -> send(UUID.randomUUID(), OBSERVED_AT, 0.77))
                .isInstanceOf(ProgressTimestampConflictException.class)
                .satisfies(e -> assertThat(
                        ((ProgressTimestampConflictException) e).getObservedAt())
                        .isEqualTo(OBSERVED_AT));

        assertThat(storedSegmentProgress()).isEqualByComparingTo(BigDecimal.valueOf(0.42));
    }

    @Test
    @DisplayName("더 최신 관측은 이어서 반영되고 그때마다 한 번씩 올라간다")
    void newerObservationApplies() {
        long start = currentEvaluationVersion();
        send(UUID.randomUUID(), OBSERVED_AT, 0.10);
        send(UUID.randomUUID(), OBSERVED_AT.plusSeconds(15), 0.30);
        JourneyProgressOutcome third =
                send(UUID.randomUUID(), OBSERVED_AT.plusSeconds(30), 0.55);

        assertThat(third.result()).isEqualTo(Result.APPLIED);
        assertThat(third.journey().evaluationVersion()).isEqualTo(start + 3);
        assertThat(storedSegmentProgress()).isEqualByComparingTo(BigDecimal.valueOf(0.55));
    }

    @Test
    @DisplayName("이전 개정본을 가리키면 거절한다")
    void staleRevisionRejected() {
        assertThatThrownBy(() -> applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                command(UUID.randomUUID(), OBSERVED_AT, 0.42,
                        journey.routeRevision() + 1, 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.STALE_ROUTE_REVISION));
    }

    @Test
    @DisplayName("없는 단계는 거절한다")
    void unknownLegRejected() {
        assertThatThrownBy(() -> applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                command(UUID.randomUUID(), OBSERVED_AT, 0.42, journey.routeRevision(), 99)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_LEG_NOT_FOUND));
    }

    @Test
    @DisplayName("남의 여정은 없는 여정과 똑같이 취급한다")
    void otherUsersJourneyHidden() {
        Long stranger = newUser();

        assertThatThrownBy(() -> applyJourneyProgressUseCase.apply(stranger, journey.journeyId(),
                command(UUID.randomUUID(), OBSERVED_AT, 0.42, journey.routeRevision(), 0)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getErrorCode())
                        .isEqualTo(JourneyErrorCode.JOURNEY_NOT_FOUND));
    }

    @Test
    @DisplayName("연속 progress 는 lease 쓰기를 합친다")
    void leaseWriteIsCoalesced() {
        Instant firstLease = storedLeaseUntil();

        // 60초 합치기 창 안에서 세 번 보낸다. 첫 progress 는 시작 직후라 이미 lease 가 최신이다.
        send(UUID.randomUUID(), OBSERVED_AT, 0.10);
        send(UUID.randomUUID(), OBSERVED_AT.plusSeconds(15), 0.20);
        send(UUID.randomUUID(), OBSERVED_AT.plusSeconds(30), 0.30);

        assertThat(storedLeaseUntil()).isEqualTo(firstLease);
    }

    @Test
    @DisplayName("합치기 창을 넘긴 뒤 오는 progress 는 lease 를 실제로 연장한다")
    void leaseExtendsAfterCoalesceWindow() {
        Instant before = storedLeaseUntil();
        // 마지막 신호를 과거로 밀어 "60초가 지난 상태"를 만든다. 시계를 흔들지 않고 반대 경로를 본다.
        jdbcTemplate.update("""
                update journey_session
                set last_heartbeat_at = now() - interval '90 seconds'
                where id = ?
                """, journey.journeyId());

        send(UUID.randomUUID(), OBSERVED_AT, 0.10);

        assertThat(storedLeaseUntil()).isAfter(before);
    }

    @Test
    @DisplayName("progress 를 저장해도 정밀 좌표 컬럼은 생기지 않는다")
    void noCoordinateStored() {
        send(UUID.randomUUID(), OBSERVED_AT, 0.42);

        List<String> columns = jdbcTemplate.queryForList("""
                select column_name from information_schema.columns
                where table_name = 'journey_leg'
                """, String.class);

        assertThat(columns).noneMatch(name ->
                name.contains("latitude") || name.contains("longitude")
                        || name.equals("lat") || name.equals("lon"));
        assertThat(columns).contains("last_progress_id", "progress_observed_at");
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private JourneyProgressOutcome send(UUID progressId, Instant observedAt, double segmentProgress) {
        return applyJourneyProgressUseCase.apply(userId, journey.journeyId(),
                command(progressId, observedAt, segmentProgress, journey.routeRevision(), 0));
    }

    private JourneyProgressCommand command(UUID progressId, Instant observedAt,
                                           double segmentProgress, int revision, int legIndex) {
        return new JourneyProgressCommand(progressId, revision, legIndex,
                3, BigDecimal.valueOf(segmentProgress), BigDecimal.valueOf(segmentProgress),
                "sec-1", 1840, 420, 3, observedAt, "GOOD");
    }

    private long currentEvaluationVersion() {
        return jdbcTemplate.queryForObject(
                "select evaluation_version from journey_session where id = ?",
                Long.class, journey.journeyId());
    }

    private BigDecimal storedSegmentProgress() {
        return jdbcTemplate.queryForObject("""
                select progress_fraction from journey_leg
                where journey_id = ? and leg_index = 0 and deleted_at is null
                """, BigDecimal.class, journey.journeyId());
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
