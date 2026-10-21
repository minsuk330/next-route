package watoo.grd.nextroute.application.journey;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.application.journey.dto.SubwayTrackabilityView;
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
import watoo.grd.nextroute.application.journey.dto.SubwayTrainCandidate;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayTrainSelectionService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.config.SubwayExpressTrackingProperties;
import watoo.grd.nextroute.application.subway.dto.TrainKind;
import watoo.grd.nextroute.application.subway.service.SubwayExpressCatalog;
import watoo.grd.nextroute.application.subway.service.SubwayRealtimeArrivalReader;
import watoo.grd.nextroute.application.subway.service.SubwayStopPatternResolver;
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
    private static final String TRANSFER_SESSION_ID = "sess-subway-selection-transfer-it";
    private static final String LINE_3 = "1003";
    private static final String STATION_3 = "1003000330";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayTrainSelectionService selectionService;
    @Autowired SubwayLegBindingRepository bindingRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository sessionRepository;
    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean SubwayRealtimeArrivalReader arrivalReader;
    /** 일회용 DB 엔 역 마스터가 비어 카탈로그가 역 id 를 못 잇는다 — 급행 정차 해석만 갈아 끼운다(S3). */
    @MockBean SubwayStopPatternResolver patternResolver;
    @Autowired SubwayExpressTrackingProperties expressTracking;

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

    @Test
    @DisplayName("후보: 대기 중이면 곧 올 열차 뒤에 막 떠난 열차(kind 로 가른다)")
    void waitingCandidatesIncludeDeparted() {
        given(arrivalReader.justDeparted(LINE, STATION, 0)).willReturn(List.of(departedRow("2388")));

        List<SubwayTrainCandidate> candidates = selectionService.candidates(userId, journeyId);

        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2390", "2392", "2388");
        assertThat(candidates).extracting(SubwayTrainCandidate::kind).containsExactly(
                SubwayTrainCandidate.Kind.UPCOMING, SubwayTrainCandidate.Kind.UPCOMING,
                SubwayTrainCandidate.Kind.DEPARTED);
        assertThat(candidates.get(2).arrivalSeconds()).isNull();
    }

    @Test
    @DisplayName("탑승 중 '다른 열차예요': 떠난 열차만 후보, 곧 올 열차(뒤차)는 422, 떠난 열차로 다시 고르면 세대가 오른다")
    void reselectWhileRiding() {
        selectionService.select(userId, journeyId, "2392", 1);
        long before = versionOf();
        ride(20);
        given(arrivalReader.justDeparted(LINE, STATION, 0)).willReturn(List.of(departedRow("2386")));

        List<SubwayTrainCandidate> candidates = selectionService.candidates(userId, journeyId);
        assertThat(candidates).extracting(SubwayTrainCandidate::trainNo).containsExactly("2386");
        assertThat(candidates).extracting(SubwayTrainCandidate::kind).containsOnly(SubwayTrainCandidate.Kind.DEPARTED);

        assertThatThrownBy(() -> selectionService.select(userId, journeyId, "2390", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_NOT_IN_CANDIDATES);

        selectionService.select(userId, journeyId, "2386", 1);
        SubwayLegBinding binding = bindingRepository.findByJourneyLegId(rideLegId).orElseThrow();
        assertThat(binding.getStatus().name()).isEqualTo("CONFIRMED");
        assertThat(binding.getTrainNo()).isEqualTo("2386");
        assertThat((long) binding.getVersion()).isGreaterThan(before);
        assertThat(binding.isSelectedBeforeMovement()).as("이미 탄 열차 — 링은 승차역부터가 아니다").isFalse();
    }

    @Test
    @DisplayName("지하철을 벗어난 단계(도보)에서는 고를 수 없다 — 409")
    void cannotSelectAfterLeavingTheSubway() {
        jdbcTemplate.update("update journey_session set current_leg_index = 2 where id = ?", journeyId);

        assertThatThrownBy(() -> selectionService.candidates(userId, journeyId))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
        assertThatThrownBy(() -> selectionService.select(userId, journeyId, "2390", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.NOT_WAITING_FOR_SUBWAY);
    }

    @Test
    @DisplayName("지하 환승: 앞 지하철을 탄 채 다음 탑승 구간의 열차를 고른다(legIndex 지정)")
    void selectNextTrainDuringTransfer() {
        given(snapshotPort.find(TRANSFER_SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(null, transferPath(), Instant.now().plusSeconds(600))));
        Long user = newUser();
        Long journey = startJourneyUseCase.start(user, new StartJourneyCommand(TRANSFER_SESSION_ID, PATH_KEY, false))
                .journeyId();
        List<Integer> rides = jdbcTemplate.queryForList("""
                select leg_index from journey_leg where journey_id = ? and type = 'RIDE_SUBWAY' order by leg_index
                """, Integer.class, journey);
        assertThat(rides).hasSize(2);
        int firstRide = rides.get(0);
        int nextRide = rides.get(1);
        jdbcTemplate.update("""
                update journey_leg set route_id = ?, start_stop_id = ?
                 where journey_id = ? and leg_index in (?, ?)""", LINE_3, STATION_3, journey, nextRide - 1, nextRide);
        jdbcTemplate.update("""
                update journey_leg_route_option set way_code = 2 where journey_leg_id in (
                    select id from journey_leg where journey_id = ? and leg_index = ?)""", journey, nextRide - 1);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?", firstRide, journey);
        given(arrivalReader.arrivalsByStation(eq(LINE_3), eq(STATION_3), anyString(), eq(2))).willReturn(List.of(
                new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder()
                        .trainNo("3101").lineId(LINE_3).stationId(STATION_3).direction("하행")
                        .destinationName("오금").trainType("일반").build(), 120)));

        assertThat(selectionService.candidates(user, journey, nextRide))
                .extracting(SubwayTrainCandidate::trainNo).containsExactly("3101");
        selectionService.select(user, journey, "3101", 1, nextRide);

        assertThat(jdbcTemplate.queryForObject("""
                select b.train_no from subway_leg_binding b join journey_leg l on l.id = b.journey_leg_id
                 where l.journey_id = ? and l.leg_index = ?""", String.class, journey, nextRide)).isEqualTo("3101");
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journey);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journey);
    }

    @Test
    @DisplayName("추적 가능 여부: 서울 실시간 노선·방향·역이 있으면 예, 노선 미지원·방향 모름은 이유와 함께 아니오")
    void trackability() {
        assertThat(selectionService.trackability(userId, journeyId, 1).trackable()).isTrue();

        jdbcTemplate.update("""
                update journey_leg_route_option set way_code = null where journey_leg_id in (
                    select id from journey_leg where journey_id = ?)""", journeyId);
        assertThat(selectionService.trackability(userId, journeyId, 1).reason())
                .isEqualTo(SubwayTrackabilityView.Reason.UNKNOWN_DIRECTION);

        jdbcTemplate.update("update journey_leg set route_id = 'INCHEON1' where journey_id = ? and type = 'WAIT_SUBWAY'",
                journeyId);
        SubwayTrackabilityView view = selectionService.trackability(userId, journeyId, 1);
        assertThat(view.trackable()).isFalse();
        assertThat(view.reason()).isEqualTo(SubwayTrackabilityView.Reason.NO_REALTIME_LINE);
    }

    @Test
    @DisplayName("오프라인 보호 연장: 그 탑승 중일 때만, 늘리기만 한다")
    void subwayProtectionOnlyExtends() {
        ride(5);
        Instant later = Instant.now().plusSeconds(1800);
        Instant earlier = Instant.now().plusSeconds(600);

        assertThat(sessionRepository.extendSubwayProtection(journeyId, 1, 1, later)).isEqualTo(1);
        assertThat(sessionRepository.extendSubwayProtection(journeyId, 1, 1, earlier)).isZero();
        assertThat(sessionRepository.extendSubwayProtection(journeyId, 1, 0, later.plusSeconds(60))).isZero();
        Instant stored = jdbcTemplate.queryForObject(
                "select subway_offline_protection_until from journey_session where id = ?",
                java.sql.Timestamp.class, journeyId).toInstant();
        assertThat(stored).isCloseTo(later, org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    /** 탑승 단계로 옮긴다(분 전에 탔다). */
    private void ride(int minutesAgo) {
        jdbcTemplate.update("update journey_session set current_leg_index = 1 where id = ?", journeyId);
        jdbcTemplate.update("update journey_leg set state = 'ACTIVE', activated_at = now() - make_interval(mins => ?) "
                + "where id = ?", minutesAgo, rideLegId);
    }

    private long versionOf() {
        return jdbcTemplate.queryForObject("select version from subway_leg_binding where journey_leg_id = ?",
                Long.class, rideLegId);
    }

    private PathResult transferPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(10).names("강남", "교대")
                                .subwayStations(900222, 900223).wayCode(0)
                                .lanes(SubPathFixture.subwayLane("2호선", 2)).build(),
                        SubPathFixture.subway(10).names("교대", "고속터미널")
                                .subwayStations(900330, 900339).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("3호선", 3)).build(),
                        SubPathFixture.walk(5).names("고속터미널", "목적지").distance(300.0).build()),
                List.of());
    }

    private List<Long> ringTargetIds() {
        return bindingRepository.findRingTargets(500).stream().map(SubwayLegBinding::getJourneyLegId).toList();
    }

    private String statusOf() {
        return jdbcTemplate.queryForObject("select status from subway_leg_binding where journey_leg_id = ?",
                String.class, rideLegId);
    }

    @Test
    @DisplayName("S3 플래그 기본값: subway.express.tracking.enabled=false")
    void expressTrackingIsOffByDefault() {
        assertThat(expressTracking.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("S3 후보: 열차 종류·표시 이름·정차 여부·추적 가능을 싣는다 — 급행은 플래그 꺼짐이면 고를 수 없다")
    void candidatesCarryKindAndStops() {
        given(arrivalReader.arrivalsByStation(eq(LINE), eq(STATION), anyString(), eq(0))).willReturn(List.of(
                arrival("2390", "성수", 90), arrival("2392", "성수", 330, "급행")));
        given(patternResolver.resolve(eq(LINE), eq("내선"), eq("2392"), any(), eq(STATION)))
                .willReturn(Optional.of(pattern(STATION, "1002000240")));

        List<SubwayTrainCandidate> candidates = selectionService.candidates(userId, journeyId);

        assertThat(candidates.get(0).trainKind()).isEqualTo(TrainKind.NORMAL);
        assertThat(candidates.get(0).trainLabel()).isEqualTo("일반");
        assertThat(candidates.get(0).trackable()).isTrue();
        assertThat(candidates.get(1).trainKind()).isEqualTo(TrainKind.EXPRESS);
        assertThat(candidates.get(1).express()).isTrue();
        assertThat(candidates.get(1).expressServiceId()).isEqualTo("it-express");
        assertThat(candidates.get(1).stopsAtBoarding()).isTrue();
        assertThat(candidates.get(1).trackable()).isFalse();
    }

    @Test
    @DisplayName("S3 선택 422 두 종: 탑승역에 서지 않는 급행 → TRAIN_SKIPS_STATION, 정차역 모르는 급행 → TRAIN_NOT_TRACKABLE."
            + " 둘 다 고정 행이 생기지 않는다")
    void selectRejectsUntrackableExpress() {
        given(arrivalReader.arrivalsByStation(eq(LINE), eq(STATION), anyString(), eq(0))).willReturn(List.of(
                arrival("2392", "성수", 330, "급행"), arrival("2394", "성수", 600, "급행")));
        given(patternResolver.resolve(eq(LINE), eq("내선"), eq("2392"), any(), eq(STATION)))
                .willReturn(Optional.of(pattern("1002000221", "1002000240")));

        assertThatThrownBy(() -> selectionService.select(userId, journeyId, "2392", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_SKIPS_STATION);
        assertThatThrownBy(() -> selectionService.select(userId, journeyId, "2394", 1))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(JourneyErrorCode.TRAIN_NOT_TRACKABLE);
        assertThat(jdbcTemplate.queryForObject("select count(*) from subway_leg_binding where journey_leg_id = ?",
                Integer.class, rideLegId)).isZero();
    }

    private static SubwayStopPatternResolver.ResolvedPattern pattern(String... stopIds) {
        return new SubwayStopPatternResolver.ResolvedPattern(new SubwayExpressCatalog.ExpressService("it-express",
                LINE, "급행", "내선", "it", null, List.of(), false), "내선", List.of(stopIds), null);
    }

    private static SubwayRealtimeArrivalReader.Arrival departedRow(String trainNo) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId(LINE).stationId(STATION).direction("내선")
                .destinationName("성수").trainType("일반").arrivalCode("2").build(), null);
    }

    private static SubwayRealtimeArrivalReader.Arrival arrival(String trainNo, String destination, int seconds,
                                                               String trainType) {
        return new SubwayRealtimeArrivalReader.Arrival(SubwayRealtimeTrain.builder()
                .trainNo(trainNo).lineId(LINE).stationId(STATION).direction("내선")
                .destinationName(destination).trainType(trainType).build(), seconds);
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
