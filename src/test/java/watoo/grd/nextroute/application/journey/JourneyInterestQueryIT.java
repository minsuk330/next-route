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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.dto.JourneyCommand;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.port.in.ApplyJourneyEventUseCase;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationProperties;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTier;
import watoo.grd.nextroute.application.journey.reeval.ReevaluationTrigger;
import watoo.grd.nextroute.application.journey.reeval.TriggerResult;
import watoo.grd.nextroute.application.journey.service.JourneyDueClaimService;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * 이벤트 기반 재평가 트리거(#415-3)의 DB 계약.
 *
 * <p>핵심은 "처리 중이면 표시만, 아니면 앞당김"이다. 처리 중인 여정의 선점 증서를 덮으면 진행 중 계산이
 * 결과를 버리고, 이벤트가 계산 시간보다 잦은 여정은 영영 저장되지 않는다(livelock).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("관심 키 SQL")
class JourneyInterestQueryIT {

    private static final String SESSION_ID = "sess-interest-query-it";
    private static final String PATH_KEY = "0";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired JourneySessionRepository sessionRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;

    private final List<Long> createdUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, busPath(), Instant.now().plusSeconds(600))));
    }

    @AfterEach
    void cleanUp() {
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    @Test
    @DisplayName("남은 대기 구간마다 한 줄 — 버스 정류장·노선이 실린다")
    void remainingWaitLegs() {
        Long id = startJourney();

        List<JourneySessionRepository.InterestRow> rows = rowsOf(id);

        assertThat(rows).extracting(JourneySessionRepository.InterestRow::getLegType).containsOnly("WAIT_BUS");
        assertThat(rows).extracting(JourneySessionRepository.InterestRow::getStopId)
                .containsExactlyInAnyOrder("ST-A", "ST-G");
        assertThat(rows).allSatisfy(row -> assertThat(row.getRouteId()).isNotNull());
    }

    @Test
    @DisplayName("지나온 대기 구간은 빠진다")
    void passedWaitLegsAreExcluded() {
        Long id = startJourney();
        Integer secondWait = jdbcTemplate.queryForObject("""
                select max(leg_index) from journey_leg l join journey_session s
                  on s.id = l.journey_id and s.route_revision = l.route_revision
                 where s.id = ? and l.type = 'WAIT_BUS'""", Integer.class, id);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?", secondWait, id);

        assertThat(rowsOf(id)).extracting(JourneySessionRepository.InterestRow::getStopId)
                .containsExactly("ST-G");
    }

    @Test
    @DisplayName("끝난 여정·단독 추적·삭제된 여정은 지켜보지 않는다")
    void inactiveJourneysAreExcluded() {
        Long ended = startJourney();
        Long track = startJourney();
        Long deleted = startJourney();
        jdbcTemplate.update("update journey_session set status = 'EXPIRED', end_reason = 'CLIENT_LEASE_EXPIRED', ended_at = now(), next_evaluation_at = null where id = ?", ended);
        jdbcTemplate.update("update journey_session set kind = 'TRACK' where id = ?", track);
        jdbcTemplate.update("update journey_session set deleted_at = now() where id = ?", deleted);

        assertThat(rowsOf(ended)).isEmpty();
        assertThat(rowsOf(track)).isEmpty();
        assertThat(rowsOf(deleted)).isEmpty();
    }

    @Test
    @DisplayName("남은 환승의 가장 가까운 준비 시각과 위험 여부가 실린다")
    void carriesNearestReadyTimeAndRisk() {
        Long id = startJourney();
        Instant ready = Instant.now().plusSeconds(240).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        int updated = jdbcTemplate.update("""
                update transfer_connection set user_ready_at = ?, status = 'AT_RISK'
                 where journey_id = ?""", java.sql.Timestamp.from(ready), id);
        assertThat(updated).as("버스 두 번이면 환승 하나").isPositive();

        List<JourneySessionRepository.InterestRow> rows = rowsOf(id);

        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getNextReadyAt()).isEqualTo(ready);
            assertThat(row.getRisky()).isTrue();
        });
    }

    @Test
    @DisplayName("조건부 조회용 버전(#415-6): 명령·평가 번호와 지금 단계 종류를 한 번에, 남의 여정은 비어 있다")
    void versionsForConditionalQuery() {
        Long id = startJourney();
        Long owner = jdbcTemplate.queryForObject("select user_id from journey_session where id = ?", Long.class, id);
        jdbcTemplate.update("update journey_session set evaluation_version = 7 where id = ?", id);

        var row = sessionRepository.findVersions(id, owner).orElseThrow();

        assertThat(row.getEvaluationVersion()).isEqualTo(7L);
        assertThat(row.getCommandVersion()).isPositive();
        assertThat(row.getCurrentLegType()).isNotNull();
        assertThat(sessionRepository.findVersions(id, owner + 999_999)).isEmpty();
    }

    private List<JourneySessionRepository.InterestRow> rowsOf(Long journeyId) {
        return sessionRepository.findInterestRows().stream()
                .filter(row -> row.getJourneyId().equals(journeyId))
                .toList();
    }

    private Long startJourney() {
        Long ownerId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(ownerId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        // 이 시험 DB 에는 정류장 해석 표가 비어 정류장 ID 가 원천 값에만 남는다. 운영처럼 해석된 상태로 둔다.
        jdbcTemplate.update("""
                update journey_leg set start_stop_id = source_start_stop_id
                 where journey_id = ? and start_stop_id is null""", journey.journeyId());
        jdbcTemplate.update("""
                update journey_leg_route_option set route_id = source_route_id
                 where route_id is null and journey_leg_id in (
                       select id from journey_leg where journey_id = ?)""", journey.journeyId());
        return journey.journeyId();
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
        jdbcTemplate.update("""
                delete from journey_notification where journey_id in (
                    select id from journey_session where user_id = ?)
                """, id);
        jdbcTemplate.update("delete from journey_session where user_id = ?", id);
    }

    private PathResult busPath() {
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
