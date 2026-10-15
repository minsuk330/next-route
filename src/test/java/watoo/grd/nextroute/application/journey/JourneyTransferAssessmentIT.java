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
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.JourneySnapshotEtaComposer;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.TransferArrival;
import watoo.grd.nextroute.application.route.dto.TransferPredictionResult;
import watoo.grd.nextroute.application.route.port.in.PredictTransferUseCase;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

/**
 * 환승 판정 결과가 DB 에 실제로 저장되는지 확인한다.
 *
 * <p>이 경로에는 단위테스트만 있었다. 저장소를 가짜로 두면 값이 컬럼에 들어가는지는 확인되지
 * 않는다 — 길이·제약 위반은 실제 DB 만 잡아낸다. 환승이 있는 여정은 조회할 때마다 이 저장이
 * 일어나므로, 여기서 깨지면 여정 조회 자체가 실패한다.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        // 조회가 판정을 계산·저장하는 것은 평가기가 꺼진 경로뿐이다(JourneySnapshotEtaComposer.assess). 켜져 있으면
        // 조회는 평가기가 남긴 값을 읽기만 한다(9f2e3f72, 그 경로는 JourneyEvalReadPathIT 가 본다). yaml 기본값이
        // 8961bed1(2026-08-25)에 true 로 바뀌면서 이 시험이 엉뚱한 경로를 타게 됐다 — 꺼진 경로는 롤백 스위치로
        // 남아 있으므로 여기서 명시해 고정한다.
        "journey.navigation.evaluator.enabled=false"
})
@DisplayName("환승 판정 저장")
class JourneyTransferAssessmentIT {

    private static final String SESSION_ID = "sess-transfer-assessment-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired JourneySnapshotEtaComposer etaComposer;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean PredictTransferUseCase predictTransferUseCase;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private JourneySnapshot journey;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, twoBusPath(), Instant.now().plusSeconds(600))));
        // 실시간 도착은 이 시험의 관심이 아니다. 모른다고 답해도 판정 행은 저장돼야 한다.
        given(predictTransferUseCase.predict(anyString(), anyString(), any(), any(), any(), anyLong()))
                .willAnswer(call -> TransferPredictionResult.noResult(
                        call.getArgument(0), call.getArgument(1), call.getArgument(2),
                        TransferArrival.Source.NONE, TransferArrival.Status.NO_VEHICLE,
                        Instant.now(), call.getArgument(3)));
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
    @DisplayName("환승이 있는 여정을 조회하면 판정이 저장되고 조회가 실패하지 않는다")
    void assessmentIsPersisted() {
        assertThat(transferCount()).isPositive();

        assertThatCode(() -> etaComposer.compose(userId, journey)).doesNotThrowAnyException();

        assertThat(storedSource()).isNotNull();
        assertThat(storedCalculatedAt()).isNotNull();
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private int transferCount() {
        return jdbcTemplate.queryForObject("""
                select count(*) from transfer_connection
                where journey_id = ? and deleted_at is null
                """, Integer.class, journey.journeyId());
    }

    private String storedSource() {
        return jdbcTemplate.queryForObject("""
                select source from transfer_connection
                where journey_id = ? and transfer_index = 0 and deleted_at is null
                """, String.class, journey.journeyId());
    }

    private Instant storedCalculatedAt() {
        return jdbcTemplate.queryForObject("""
                select calculated_at from transfer_connection
                where journey_id = ? and transfer_index = 0 and deleted_at is null
                """, Instant.class, journey.journeyId());
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
                                .lanes(SubPathFixture.busLane("721", "BL-721")).build()),
                List.of());
    }
}
