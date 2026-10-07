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
import watoo.grd.nextroute.application.journey.dto.SubwayBoardingEvidence;
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.SubwayBindingUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayBindingMatchService;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingConfidence;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * 역에서 고른 열차(#265 PR-3)가 실제 DB 에서 지켜야 할 것.
 *
 * <ul>
 *   <li>선택 → CONFIRMED · USER_SELECTED · 열차 식별 4칸 + 감사 USER_SELECTED (V60 제약 통과)</li>
 *   <li>고르는 즉시 링 대상(#304). 이동 관측이 와도 상태는 그대로 확정·링 대상 유지, 판정기는 안 본다</li>
 *   <li>이동 전 "이 열차 아님" 은 타는 단계가 시작 전이면 6분이 지나도 안 접힌다 → 이후 이동 관측이 오면 판정 대상</li>
 *   <li>출처가 비어 있는(정차 관측이 먼저 연) 고정도 링 대상에서 빠지지 않는다 — null 안전 조건 회귀</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.subway.selection.enabled=true"
})
@DisplayName("지하철 열차 선택 IT")
class SubwayTrainSelectionIT {

    private static final String SESSION_ID = "sess-subway-selection-it";
    private static final String PATH_KEY = "0";
    private static final String LINE = "1002";
    private static final String STATION = "1002000222";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayTrainSelectionService selectionService;
    @Autowired SubwayBindingUseCase bindingUseCase;
    @Autowired SubwayBindingMatchService matchService;
    @Autowired SubwayLegBindingRepository bindingRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean SubwayRealtimeArrivalReader arrivalReader;

    private final List<Long> users = new ArrayList<>();
    private Long userId;
    private Long journeyId;
    private Long rideLegId;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, subwayPath(), Instant.now().plusSeconds(600))));
        userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId, new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        rideLegId = jdbcTemplate.queryForObject(
                "select id from journey_leg where journey_id = ? and type = 'RIDE_SUBWAY'", Long.class, journeyId);
        // 일회용 DB 에는 역 표가 비어 보강이 역 id 를 못 채운다. 대기 단계가 실제 운영처럼 보이게 직접 채운다.
        jdbcTemplate.update("""
                update journey_leg set route_id = ?, start_stop_id = ?
                 where journey_id = ? and type in ('WAIT_SUBWAY','RIDE_SUBWAY')""", LINE, STATION, journeyId);
        jdbcTemplate.update("""
                update journey_leg_route_option set way_code = 0 where journey_leg_id in (
                    select id from journey_leg where journey_id = ?)""", journeyId);
        given(arrivalReader.arrivalsByStation(eq(LINE), eq(STATION), anyString(), eq(0))).willReturn(List.of(
                arrival("2390", "성수", 90), arrival("2392", "성수", 330), arrival("2394", "성수", 600)));
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        for (Long id : users) {
            jdbcTemplate.update("""
                    update journey_leg set selected_route_option_id = null
                     where journey_id in (select id from journey_session where user_id = ?)""", id);
            jdbcTemplate.update("""
                    delete from journey_leg_route_option where journey_leg_id in (
                        select l.id from journey_leg l join journey_session s on s.id = l.journey_id
                         where s.user_id = ?)""", id);
            for (String table : List.of("journey_leg", "journey_revision", "transfer_connection", "journey_event",
                    "journey_notification")) {
                jdbcTemplate.update("delete from " + table
                        + " where journey_id in (select id from journey_session where user_id = ?)", id);
            }
            jdbcTemplate.update("delete from journey_session where user_id = ?", id);
            jdbcTemplate.update("delete from users where id = ?", id);
        }
        users.clear();
    }

    @Test
    @DisplayName("후보: 대기 단계의 역·방향 다음 2대")
    void candidates() {
        List<SubwayTrainCandidate> candidates = selectionService.candidates(userId, journeyId);

        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2390", "2392");
        assertThat(candidates.get(0).destination()).isEqualTo("성수");
        assertThat(candidates.get(0).arrivalSeconds()).isEqualTo(90);
    }

    @Test
    @DisplayName("선택 → CONFIRMED·USER_SELECTED·식별 4칸, 감사 USER_SELECTED. 이동 전에도 바로 링 대상(#304)")
    void selectConfirmsWithFullIdentity() {
        selectionService.select(userId, journeyId, "2392", 1);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "select * from subway_leg_binding where journey_leg_id = ?", rideLegId);
        assertThat(row.get("status")).isEqualTo("CONFIRMED");
        assertThat(row.get("source")).isEqualTo("USER_SELECTED");
        assertThat(row.get("train_no")).isEqualTo("2392");
        assertThat(row.get("train_service_date")).isNotNull();
        assertThat(row.get("train_line_id")).isEqualTo(LINE);
        assertThat(row.get("train_direction")).isEqualTo("내선");
        assertThat(row.get("last_movement_seq")).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from subway_binding_event
                 where journey_leg_id = ? and event_type = 'USER_SELECTED' and kind = 'DECISION'
                   and chosen_train_no = '2392'""", Integer.class, rideLegId)).isEqualTo(1);
        assertThat(ringTargetIds()).contains(rideLegId);
    }

    @Test
    @DisplayName("선택 뒤 이동 관측 → 상태 그대로, 순번·감지 시각 채움 → 링 대상 유지. 판정기는 안 본다")
    void movementAfterSelectionKeepsRing() {
        selectionService.select(userId, journeyId, "2390", 1);

        movementStart(1);

        SubwayLegBinding binding = bindingRepository.findByJourneyLegId(rideLegId).orElseThrow();
        assertThat(binding.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(binding.getTrainNo()).isEqualTo("2390");
        assertThat(binding.getLastMovementSeq()).isEqualTo(1);
        assertThat(binding.getBoardedDetectedAt()).isNotNull();
        assertThat(ringTargetIds()).contains(rideLegId);
        assertThat(bindingRepository.findMatchable(500)).extracting(SubwayLegBinding::getJourneyLegId)
                .doesNotContain(rideLegId);
    }

    @Test
    @DisplayName("이동 전 '이 열차 아님' — 타기 전이면 6분 지나도 안 접힌다, 이동 관측이 오면 자동 판정 대상")
    void resetBeforeMovementKeepsM7Alive() {
        selectionService.select(userId, journeyId, "2390", 1);
        bindingUseCase.reset(userId, journeyId, 1, 1);
        jdbcTemplate.update("update subway_leg_binding set updated_at = now() - interval '30 minutes' "
                + "where journey_leg_id = ?", rideLegId);

        matchService.sweepStale();

        assertThat(statusOf()).isEqualTo("PENDING");
        movementStart(1);
        assertThat(bindingRepository.findMatchable(500)).extracting(SubwayLegBinding::getJourneyLegId)
                .contains(rideLegId);
    }

    @Test
    @DisplayName("타는 단계가 시작된 뒤 근거 없는 PENDING 은 그대로 접힌다(기존 동작)")
    void sweepStillFoldsWhenRiding() {
        selectionService.select(userId, journeyId, "2390", 1);
        bindingUseCase.reset(userId, journeyId, 1, 1);
        jdbcTemplate.update("update journey_leg set state = 'ACTIVE' where id = ?", rideLegId);
        jdbcTemplate.update("update subway_leg_binding set updated_at = now() - interval '30 minutes' "
                + "where journey_leg_id = ?", rideLegId);

        matchService.sweepStale();

        assertThat(statusOf()).isEqualTo("ABANDONED");
    }

    @Test
    @DisplayName("출처가 비어 있는 확정 행도 링 대상 — null 안전 조건")
    void nullSourceStaysInRing() {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index, status,
                     train_no, train_service_date, train_line_id, train_direction, version, reset_generation,
                     last_movement_seq, source)
                values (now(), now(), ?, ?, 1, 1, 'PROVISIONAL', '2390', current_date, ?, '내선', 0, 0, 1, null)
                """, rideLegId, journeyId, LINE);

        assertThat(ringTargetIds()).contains(rideLegId);
    }

    private List<Long> ringTargetIds() {
        return bindingRepository.findRingTargets(500).stream().map(SubwayLegBinding::getJourneyLegId).toList();
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject("select status from subway_leg_binding where journey_leg_id = ?",
                String.class, rideLegId);
    }

    private void movementStart(int seq) {
        Instant t = Instant.now();
        bindingUseCase.submit(userId, journeyId, 1, 1, new SubwayBoardingEvidence(
                SubwayBindingEventType.MOVEMENT_START, seq, t, null, null,
                "STATIONARY", "AUTOMOTIVE", SubwayBindingConfidence.HIGH, 10, null, t));
    }

    private static SubwayRealtimeArrivalReader.Arrival arrival(String trainNo, String destination, int seconds) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId(LINE).stationId(STATION).direction("내선")
                .destinationName(destination).trainType("일반").build(), seconds);
    }

    private Long newUser() {
        Long id = jdbcTemplate.queryForObject("""
                insert into users (created_at, updated_at, token_generation)
                values (now(), now(), 0) returning id""", Long.class);
        users.add(id);
        return id;
    }

    private PathResult subwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(15).names("강남", "신촌")
                                .subwayStations(900222, 900240).wayCode(0)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.walk(5).names("신촌", "목적지").distance(300.0).build()),
                List.of());
    }
}
