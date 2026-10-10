package watoo.grd.nextroute.application.journey;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingEtaSource;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayBindingLifecycleService;
import watoo.grd.nextroute.application.journey.service.SubwayRingUpdateService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeSnapshot;
import watoo.grd.nextroute.application.subway.dto.SubwayRealtimeTrain;
import watoo.grd.nextroute.application.subway.port.out.SubwayRealtimeCachePort;
import watoo.grd.nextroute.application.subway.port.out.SubwayTrainLogPort;
import watoo.grd.nextroute.application.subway.port.out.SubwaySegmentTimePort;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.infrastructure.adapter.in.scheduler.journey.SubwayRingUpdateScheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 진행 링 갱신기가 <b>무엇을 보고 무엇을 안 보는지</b>를 실제 DB 로 고정한다.
 *
 * <p>가장 조용한 사고 셋 —
 * <ul>
 *   <li><b>미확정 고정으로 링을 만든다</b>: 아직 어느 열차인지 모르는데 다음 역과 진행률이
 *       그려지고, 앱은 그것을 실시간으로 읽는다</li>
 *   <li><b>끝난 여정·옛 개정본을 계속 훑는다</b>: 이 표는 행을 지우지 않아 스스로 멈추지 않는다</li>
 *   <li><b>표시 이름으로 다음 역을 판정한다</b>: 역명 표기가 흔들리면 같은 역인데 링이 리셋된다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false"
})
@DisplayName("진행 링 갱신기의 스캔·판정 계약")
class SubwayRingIT {

    private static final String SESSION_ID = "sess-subway-ring-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 15);
    private static final String TRAIN_NO = "2233";
    private static final String DIRECTION = "내선";
    private static final DateTimeFormatter RECEIVED_AT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** 실시간 {@code statnId}. ODsay 의 역 식별자와 <b>다른 체계</b>라 일부러 안 겹치게 둔다. */
    private static final String STATN_BOARD = "1002000222";
    private static final String STATN_MIDDLE = "1002000223";
    private static final String STATN_ALIGHT = "1002000224";

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayLegBindingRepository bindingRepository;
    @Autowired SubwayRingUpdateService updateService;
    @Autowired SubwayBindingLifecycleService lifecycleService;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired ApplicationContext applicationContext;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean SubwayRingPort ringPort;
    @MockBean SubwayRealtimeCachePort realtimeCachePort;
    @MockBean SubwayTrainLogPort trainLogPort;
    @MockBean SubwaySegmentTimePort segmentTimePort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long journeyId;
    private Long rideLegId;
    private int rideLegIndex;
    private String lineId;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), java.time.Instant.now().plusSeconds(600))));
        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.empty());
        given(trainLogPort.lastSeen(any())).willReturn(Optional.empty());
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.empty());

        Long userId = newUser();
        JourneySnapshot journey = startJourneyUseCase.start(userId,
                new StartJourneyCommand(SESSION_ID, PATH_KEY, false));
        journeyId = journey.journeyId();
        rideLegId = jdbcTemplate.queryForObject("""
                select id from journey_leg
                 where journey_id = ? and type = 'RIDE_SUBWAY'
                 order by leg_index limit 1
                """, Long.class, journeyId);
        rideLegIndex = jdbcTemplate.queryForObject(
                "select leg_index from journey_leg where id = ?", Integer.class, rideLegId);
        lineId = jdbcTemplate.queryForObject(
                "select route_id from journey_leg where id = ?", String.class, rideLegId);

        // 실시간 역 마스터. 표기가 계획과 다르다 — 정규화로 이어 붙는 것이 계약이다.
        insertStation(STATN_BOARD, "승차역");
        insertStation(STATN_MIDDLE, "중간역");
        insertStation(STATN_ALIGHT, "하차역");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_station where line_id = ?", lineId);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 스캔 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("확정된 고정만 훑는다")
    void scansOnlyConfirmed() {
        insertBinding("CONFIRMED");

        assertThat(ringTargets()).containsExactly(rideLegId);
    }

    @Test
    @DisplayName("잠정 고정도 훑는다 — 후보가 하나뿐인 한산한 시간대에도 링이 떠야 한다(2026-09-29 결정)")
    void scansProvisional() {
        insertBinding("PROVISIONAL");

        assertThat(ringTargets()).containsExactly(rideLegId);
    }

    @Test
    @DisplayName("PENDING 은 훑지 않는다 — 아직 어느 열차인지 하나도 고르지 못했다")
    void skipsPending() {
        insertBinding("PROVISIONAL");
        jdbcTemplate.update(
                "update subway_leg_binding set status = 'PENDING' where journey_leg_id = ?",
                rideLegId);

        assertThat(ringTargets()).isEmpty();
    }

    @Test
    @DisplayName("끝난 여정은 훑지 않는다 — 빠뜨리면 영원히 갱신된다")
    void skipsEndedJourneys() {
        insertBinding("CONFIRMED");
        jdbcTemplate.update(
                "update journey_session set status = 'COMPLETED', ended_at = now() where id = ?",
                journeyId);

        assertThat(ringTargets()).isEmpty();
    }

    @Test
    @DisplayName("옛 개정본은 훑지 않는다")
    void skipsSupersededRevisions() {
        insertBinding("CONFIRMED");
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?", journeyId);

        assertThat(ringTargets()).isEmpty();
    }

    // ── 판정 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("다음 역을 표시 이름이 아니라 canonical key 로 집는다")
    void picksNextStationByCanonicalKey() {
        insertBinding("CONFIRMED");
        // 우리 열차가 '중간역'(statnId 기준)까지 90초 남았다. 응답의 표시 이름은 흔들려 있다.
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(
                arrivalRow(STATN_MIDDLE, "중간역종착", "99", 90))));

        updateService.runOnce();

        SubwayRingState saved = savedRing();
        assertThat(saved.canonicalNextStationKey()).isEqualTo(STATN_MIDDLE);
        assertThat(saved.nextStationName()).isEqualTo("중간역");
        assertThat(saved.nextStationSequence()).isEqualTo(1);
        assertThat(saved.remainingStations()).isEqualTo(2);
        assertThat(saved.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(saved.estimatedNextStationAt()).isNotNull();
    }

    @Test
    @DisplayName("서버가 근거를 처음 본 시각: 같은 행(같은 recptnDt)이 다시 오면 이어 쓰고, 새 행이면 새로 찍는다")
    void sourceSeenAtCarriesOverForTheSameRow() {
        insertBinding("CONFIRMED");
        SubwayRealtimeTrain row = arrivalRow(STATN_MIDDLE, "중간역", "99", 90);
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(row)));

        updateService.runOnce();
        SubwayRingState first = savedRing();
        assertThat(first.sourceSeenAt()).isNotNull();
        assertThat(first.sourceObservedAt()).isBefore(first.sourceSeenAt());
        assertThat(first.etaSource()).isEqualTo(SubwayRingEtaSource.LIVE);

        org.mockito.Mockito.clearInvocations(ringPort);
        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.of(first));
        updateService.runOnce();
        SubwayRingState second = savedRing();
        assertThat(second.sourceSeenAt()).as("같은 행은 처음 본 시각을 잇는다").isEqualTo(first.sourceSeenAt());

        org.mockito.Mockito.clearInvocations(ringPort);
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(SubwayRealtimeTrain.builder()
                .trainNo(TRAIN_NO).stationId(STATN_MIDDLE).stationName("중간역").lineId(lineId)
                .direction(DIRECTION).arrivalCode("99").arrivalSeconds(80)
                .receivedAt(LocalDateTime.now(SEOUL).plusSeconds(1).format(RECEIVED_AT)).build())));
        updateService.runOnce();
        SubwayRingState third = savedRing();
        assertThat(third.sourceSeenAt()).as("새 행은 지금으로 찍는다").isAfterOrEqualTo(first.sourceSeenAt());
        assertThat(third.sourceObservedAt()).isAfter(first.sourceObservedAt());
    }

    @Test
    @DisplayName("키에 개정본과 고정 세대가 그대로 실린다")
    void ringCarriesTheGeneration() {
        insertBinding("CONFIRMED");
        jdbcTemplate.update(
                "update subway_leg_binding set version = 4 where journey_leg_id = ?", rideLegId);
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(
                arrivalRow(STATN_MIDDLE, "중간역", "99", 90))));

        updateService.runOnce();

        SubwayRingState saved = savedRing();
        assertThat(saved.routeRevision()).isEqualTo(1);
        assertThat(saved.legIndex()).isEqualTo(rideLegIndex);
        assertThat(saved.bindingVersion()).isEqualTo(4);
    }

    @Test
    @DisplayName("실시간이 아무것도 없으면 STALE 로 멈춘다 — 계획 시간으로 채우지 않는다")
    void withoutRealtimeTheRingStalls() {
        insertBinding("CONFIRMED");

        updateService.runOnce();

        SubwayRingState saved = savedRing();
        assertThat(saved.quality()).isEqualTo(SubwayRingQuality.STALE);
        assertThat(saved.estimatedNextStationAt()).isNull();
        assertThat(saved.segmentProgress()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("잠정 고정에도 링을 만든다 — 확정과 같은 모양이다")
    void provisionalBindingWritesRing() {
        insertBinding("PROVISIONAL");
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(
                arrivalRow(STATN_MIDDLE, "중간역", "99", 90))));

        updateService.runOnce();

        SubwayRingState saved = savedRing();
        assertThat(saved.nextStationName()).isEqualTo("중간역");
        assertThat(saved.quality()).isEqualTo(SubwayRingQuality.NORMAL);
        assertThat(saved.estimatedNextStationAt()).isNotNull();
    }

    @Test
    @DisplayName("PENDING 에는 링을 만들지 않는다")
    void pendingBindingWritesNoRing() {
        insertBinding("PROVISIONAL");
        jdbcTemplate.update(
                "update subway_leg_binding set status = 'PENDING' where journey_leg_id = ?",
                rideLegId);

        updateService.runOnce();

        verify(ringPort, never()).save(any());
    }

    // ── 정리 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("여정이 끝나면 링이 지워진다 — 색인으로 지우므로 SCAN 이 없다")
    void ringIsClearedWhenJourneyEnds() {
        insertBinding("CONFIRMED");

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        verify(ringPort).deleteByJourney(journeyId);
    }

    @Test
    @DisplayName("트랜잭션이 되돌아가면 링을 지우지 않는다 — 링만 사라지면 화면이 뒤로 감긴다")
    void rollbackKeepsTheRing() {
        insertBinding("CONFIRMED");

        transactionTemplate.executeWithoutResult(status -> {
            lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED);
            status.setRollbackOnly();
        });

        verify(ringPort, never()).deleteByJourney(journeyId);
    }

    // ── 역에서 고른 열차 (#304) ────────────────────────────────────────────

    @Test
    @DisplayName("역에서 고르고 아직 안 움직인 열차 — 바로 훑고, 첫 링은 승차역을 가리킨다")
    void userSelectedBeforeMovementRingsFromBoarding() {
        insertBinding("CONFIRMED");
        jdbcTemplate.update("""
                update subway_leg_binding set source = 'USER_SELECTED', last_movement_seq = null
                 where journey_leg_id = ?""", rideLegId);
        // 열차가 승차역까지 90초 남았다.
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(
                arrivalRow(STATN_BOARD, "승차역", "99", 90))));

        assertThat(ringTargets()).containsExactly(rideLegId);
        updateService.runOnce();

        SubwayRingState ring = savedRing();
        assertThat(ring.nextStationSequence()).isZero();
        assertThat(ring.canonicalNextStationKey()).isEqualTo(STATN_BOARD);
        assertThat(ring.remainingStations()).isEqualTo(2);
        assertThat(ring.segmentProgress()).isEqualByComparingTo("0");
        assertThat(ring.estimatedNextStationAt()).isNotNull();
    }

    // ── 앞 열차 실측 예정 (2026-10-07 결정) ─────────────────────────────────

    @Test
    @DisplayName("전역 출발 행 + 실측 표본 → 첫 링의 예정 = 출발을 처음 본 시각 + 표본 중앙값, 기준 시각 = 출발")
    void measuredEtaFromSegmentSamples() {
        insertBinding("CONFIRMED");
        SubwayRealtimeTrain departedPrev = arrivalRow(STATN_MIDDLE, "중간역", "3", 140);
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(departedPrev)));
        java.time.Instant departed = java.time.LocalDateTime.parse(departedPrev.getReceivedAt(), RECEIVED_AT)
                .atZone(SEOUL).toInstant().minusSeconds(10);
        given(segmentTimePort.recentSeconds(lineId, DIRECTION, STATN_MIDDLE)).willReturn(List.of(40L, 55L, 50L));
        given(segmentTimePort.departedAt(lineId, DIRECTION, STATN_MIDDLE, TRAIN_NO))
                .willReturn(Optional.of(departed));

        updateService.runOnce();

        SubwayRingState ring = savedRing();
        assertThat(ring.canonicalNextStationKey()).isEqualTo(STATN_MIDDLE);
        assertThat(ring.progressBaseAt()).isEqualTo(departed);
        assertThat(ring.estimatedNextStationAt()).isEqualTo(departed.plusSeconds(50));
        assertThat(ring.quality().name()).isEqualTo("NORMAL");
    }

    // ── 링 버전 (#307) ──────────────────────────────────────────────────────

    @Test
    @DisplayName("링 버전: 첫 저장에 부여, 같은 화면 tick 은 그대로, 화면이 바뀌면 오른다")
    void guidanceVersionMovesOnlyWithScreen() {
        insertBinding("CONFIRMED");
        SubwayRealtimeTrain enroute = arrivalRow(STATN_MIDDLE, "중간역", "99", 90);
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(enroute)));

        updateService.runOnce();
        SubwayRingState first = lastSaved(1);
        assertThat(first.guidanceVersion()).isPositive();

        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.of(first));
        updateService.runOnce();
        SubwayRingState same = lastSaved(2);
        assertThat(same.guidanceVersion()).isEqualTo(first.guidanceVersion());

        given(ringPort.find(anyLong(), anyInt(), anyInt(), anyInt())).willReturn(Optional.of(same));
        given(realtimeCachePort.readSnapshot()).willReturn(Optional.of(snapshotOf(
                arrivalRow(STATN_MIDDLE, "중간역", "1", 0))));
        updateService.runOnce();
        SubwayRingState arrived = lastSaved(3);
        assertThat(arrived.segmentProgress()).isEqualByComparingTo("1");
        assertThat(arrived.guidanceVersion()).isGreaterThan(first.guidanceVersion());
    }

    private SubwayRingState lastSaved(int times) {
        ArgumentCaptor<SubwayRingState> captor = ArgumentCaptor.forClass(SubwayRingState.class);
        verify(ringPort, org.mockito.Mockito.times(times)).save(captor.capture());
        return captor.getValue();
    }

    // ── 배포 스위치 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("기능이 꺼져 있으면 스케줄러 빈이 아예 없다")
    void schedulerIsAbsentWhenDisabled() {
        assertThat(applicationContext.getBeanNamesForType(SubwayRingUpdateScheduler.class))
                .isEmpty();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private SubwayRingState savedRing() {
        ArgumentCaptor<SubwayRingState> captor = ArgumentCaptor.forClass(SubwayRingState.class);
        verify(ringPort).save(captor.capture());
        return captor.getValue();
    }

    private List<Long> ringTargets() {
        return bindingRepository.findRingTargets(50).stream()
                .map(SubwayLegBinding::getJourneyLegId)
                .toList();
    }

    private SubwayRealtimeSnapshot snapshotOf(SubwayRealtimeTrain... trains) {
        return SubwayRealtimeSnapshot.builder().trains(List.of(trains)).build();
    }

    private SubwayRealtimeTrain arrivalRow(String stationId, String displayName,
                                           String arrivalCode, Integer seconds) {
        return SubwayRealtimeTrain.builder()
                .trainNo(TRAIN_NO)
                .stationId(stationId)
                .stationName(displayName)
                .lineId(lineId)
                .direction(DIRECTION)
                .arrivalCode(arrivalCode)
                .arrivalSeconds(seconds)
                .receivedAt(LocalDateTime.now(SEOUL).minusSeconds(5).format(RECEIVED_AT))
                .build();
    }

    private void insertBinding(String status) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, ?, ?, ?, ?, 0, 0, 1)
                """, rideLegId, journeyId, rideLegIndex, status, TRAIN_NO,
                java.sql.Date.valueOf(SERVICE_DATE), lineId, DIRECTION);
    }

    /**
     * 실시간 역 마스터는 계획과 표기가 다르다({@code 중간역} vs {@code 중간역(2호선)}).
     *
     * <p>이름으로 잇는 것은 여기 한 번뿐이고, 그 뒤의 판정은 전부 {@code statnId} 로만 한다.
     */
    private void insertStation(String statnId, String planName) {
        jdbcTemplate.update("""
                insert into subway_station (created_at, updated_at, line_id, statn_id, statn_nm)
                values (now(), now(), ?, ?, ?)
                """, lineId, statnId, planName + "(2호선)");
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

    /** 승차역 → 중간역 → 하차역. 세 정거장이라야 "다음 역"과 "하차역"이 갈린다. */
    private PathResult subwayPath() {
        return new PathResult(1, null,
                List.of(
                        SubPathFixture.subway(15).names("승차역", "하차역")
                                .subwayStations(900222, 900224).wayCode(2)
                                .lanes(SubPathFixture.subwayLane("2호선", 2))
                                .stations(
                                        new StationResult(0, "900222", "승차역", null, null),
                                        new StationResult(1, "900223", "중간역", null, null),
                                        new StationResult(2, "900224", "하차역", null, null))
                                .build(),
                        SubPathFixture.walk(5).names("하차역", "목적지").distance(300.0).build()),
                List.of());
    }
}
