package watoo.grd.nextroute.application.journey;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.StartJourneyCommand;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.in.StartJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;
import watoo.grd.nextroute.application.journey.service.SubPathFixture;
import watoo.grd.nextroute.application.journey.service.SubwayBindingLifecycleService;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushEnqueueService;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushRelayService;
import watoo.grd.nextroute.application.journey.service.SubwayRingPushRelayTxService;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.application.route.port.out.RouteSearchSnapshotPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushOutbox;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayRingPushOutboxRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 원격 갱신 대기열의 계약을 실제 DB 로 고정한다.
 *
 * <p>여기서 막는 사고는 전부 <b>순서</b>에 관한 것이다.
 * <ul>
 *   <li>늦게 도착한 옛 화면이 새 화면을 덮는다</li>
 *   <li>개정본이 오르며 순번이 되감겨 그 판정 자체가 무력해진다</li>
 *   <li>같은 개정본 안의 고정 리셋을 개정본 비교로는 못 잡는다</li>
 *   <li>{@code SENDING} 으로 옮기는 순간 대기 unique 가 풀려 창이 열린다</li>
 * </ul>
 */
@SpringBootTest
@TestPropertySource(properties = {
        "collector.static-data.enabled=false",
        "realtime.subway.enabled=false",
        "kma.weather.enabled=false",
        "journey.subway.ring.push.enabled=true"
})
@DisplayName("진행 링 원격 갱신 대기열 계약")
class SubwayRingPushIT {

    private static final String SESSION_ID = "sess-subway-ring-push-it";
    private static final String PATH_KEY = "0";
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 15);
    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");

    @Autowired StartJourneyUseCase startJourneyUseCase;
    @Autowired SubwayRingPushEnqueueService enqueueService;
    @Autowired SubwayRingPushRelayService relayService;
    @Autowired SubwayRingPushRelayTxService relayTxService;
    @Autowired SubwayRingPushOutboxRepository outboxRepository;
    @Autowired SubwayBindingLifecycleService lifecycleService;
    @Autowired JourneyLegRepository legRepository;
    @Autowired watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository bindingRepository;
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TransactionTemplate transactionTemplate;

    @MockBean RouteSearchSnapshotPort snapshotPort;
    @MockBean LiveActivityPushPort pushPort;

    private final List<Long> createdUserIds = new ArrayList<>();
    private Long userId;
    private Long journeyId;
    private Long rideLegId;
    private int rideLegIndex;
    private JourneyLeg rideLeg;

    @BeforeEach
    void setUp() {
        given(snapshotPort.find(SESSION_ID, PATH_KEY)).willReturn(Optional.of(
                new RouteSearchSnapshotPort.StoredPath(
                        null, subwayPath(), Instant.now().plusSeconds(600))));
        given(pushPort.send(anyString(), anyString(), anyString(), anyLong(), any()))
                .willReturn(LiveActivityPushPort.PushResult.ok());

        userId = newUser();
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
        rideLeg = legRepository.findById(rideLegId).orElseThrow();
        insertBinding("CONFIRMED", 0);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("delete from subway_ring_push_outbox where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_live_activity_token where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from user_push_token where user_id = ?", userId);
        jdbcTemplate.update("delete from subway_binding_event where journey_id = ?", journeyId);
        jdbcTemplate.update("delete from subway_leg_binding where journey_id = ?", journeyId);
        createdUserIds.forEach(this::deleteJourneysOf);
        createdUserIds.forEach(id -> jdbcTemplate.update("delete from users where id = ?", id));
        createdUserIds.clear();
    }

    // ── 넣기 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("같은 화면은 두 번 들어가지 않는다")
    void sameScreenIsEnqueuedOnce() {
        SubwayRingState ring = ring(1, "0.4500");

        enqueueService.enqueueIfChanged(null, ring, rideLeg);
        enqueueService.enqueueIfChanged(ring, ring, rideLeg);

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("새 것이 들어오면 이전 대기 행이 SKIPPED 로 밀린다")
    void newerSupersedesOpenRow() {
        SubwayRingState first = ring(1, "0.4500");
        enqueueService.enqueueIfChanged(null, first, rideLeg);

        enqueueService.enqueueIfChanged(first, ring(2, "0.0000"), rideLeg);

        assertThat(statusesBySequence()).containsExactly(
                Map.entry(1L, "SKIPPED"),
                Map.entry(2L, "PENDING"));
    }

    @Test
    @DisplayName("sequence 와 aps_timestamp 는 개정본·단계가 바뀌어도 되감기지 않는다")
    void sequenceAndTimestampAreMonotonicAcrossGenerations() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        // 재탐색: 개정본이 오르고 고정 세대는 0 으로 되돌아간다.
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?", journeyId);
        jdbcTemplate.update("""
                update subway_leg_binding set route_revision = 2, version = 0
                 where journey_leg_id = ?
                """, rideLegId);

        enqueueService.enqueueIfChanged(null, ringOfRevision(2, 1, "0.1000"), rideLeg);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                select sequence, aps_timestamp from subway_ring_push_outbox
                 where journey_id = ? order by id
                """, journeyId);
        assertThat(rows).hasSize(2);
        assertThat((Long) rows.get(1).get("sequence"))
                .as("여정 전체에서 단조 증가해야 한다")
                .isGreaterThan((Long) rows.get(0).get("sequence"));
        assertThat((Long) rows.get(1).get("aps_timestamp"))
                .as("APNs 순서의 최후 방어선이다")
                .isGreaterThan((Long) rows.get(0).get("aps_timestamp"));
    }

    @Test
    @DisplayName("중요 이벤트는 critical 로 들어간다")
    void criticalEventsAreMarked() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        assertThat(jdbcTemplate.queryForObject("""
                select critical from subway_ring_push_outbox where journey_id = ? order by id limit 1
                """, Boolean.class, journeyId)).isTrue();
    }

    @Test
    @DisplayName("payload 는 {name, props} 두 겹이고 props 는 문자열이다")
    void payloadKeepsTheEnvelope() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        JsonNode payload = payloadOf();
        assertThat(payload.get("name").asText()).isEqualTo("NavigationLiveActivity");
        assertThat(payload.get("props").isTextual()).isTrue();
        assertThat(payload.get("props").asText()).contains("\"routeName\":\"2호선\"");
    }

    @Test
    @DisplayName("앱이 못 그리는 링은 넣지 않는다 — null 하나로 갱신이 통째로 무시된다")
    void incompleteRingIsNotEnqueued() {
        SubwayRingState noName = new SubwayRingState(journeyId, 1, rideLegIndex, 0,
                1, "1002000221", /* nextStationName */ null, "하차역", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);

        enqueueService.enqueueIfChanged(null, noName, rideLeg);

        assertThat(rowCount())
                .as("순번만 태우고 앱은 아무것도 못 그린다")
                .isZero();
    }

    @Test
    @DisplayName("도착 예정이 없는 것은 정상이다 — 그때 앱은 보간을 멈춘다")
    void missingEstimateStillGoesOut() {
        SubwayRingState frozen = new SubwayRingState(journeyId, 1, rideLegIndex, 0,
                1, "1002000221", "역1", "하차역", 2,
                new BigDecimal("0.4500"), T0, new BigDecimal("0.90"),
                /* estimatedNextStationAt */ null, T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.STALE, T0, T0);

        enqueueService.enqueueIfChanged(null, frozen, rideLeg);

        assertThat(rowCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("여정·개정본·단계마다 살아 있는 대기 행은 하나다 — DB 가 소유하는 제약이다")
    void onlyOneOpenRowPerKey() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into subway_ring_push_outbox
                    (created_at, updated_at, journey_id, route_revision, leg_index,
                     binding_version, sequence, aps_timestamp, signature, critical, reason,
                     event, payload, status, attempt_count, expires_at)
                values (now(), now(), ?, 1, ?, 0, 999, 999, 'dup', false, 'anchor',
                        'update', '{}'::jsonb, 'PENDING', 0, now() + interval '2 minutes')
                """, journeyId, rideLegIndex))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── 발송 직전 재확인 ────────────────────────────────────────────────────

    @Test
    @DisplayName("집어 간 순간 대기 unique 가 풀린다 — 그 창에 새 행이 들어올 수 있다")
    void claimingOpensTheWindow() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayTxService.claim();
        // 부분 unique 는 status='PENDING' 만 보므로 SENDING 이 된 행은 술어에서 빠진다.
        enqueueService.enqueueIfChanged(ring(1, "0.4500"), ring(2, "0.0000"), rideLeg);

        assertThat(statusesBySequence()).containsExactly(
                Map.entry(1L, "SENDING"),
                Map.entry(2L, "PENDING"));
    }

    @Test
    @DisplayName("그 창에서 구형이 된 행은 발송 직전 재확인이 잡는다 — unique 하나로는 못 막는다")
    void staleRowIsCaughtByThePreSendCheck() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        relayTxService.claim();
        enqueueService.enqueueIfChanged(ring(1, "0.4500"), ring(2, "0.0000"), rideLeg);

        SubwayRingPushOutbox claimed = rowOfSequence(2L);
        SubwayRingPushOutbox stale = rowOfSequence(1L);

        assertThat(relayTxService.reasonToSkip(stale)).contains("superseded");
        assertThat(relayTxService.reasonToSkip(claimed))
                .as("최신 행은 보내야 한다")
                .isEmpty();
    }

    @Test
    @DisplayName("고정 세대가 그 사이 바뀌면 발송하지 않는다 — 같은 개정본 안의 리셋도 잡는다")
    void staleBindingGenerationIsNotSent() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        // 사용자가 "다른 열차를 탔다" → 같은 개정본인데 세대만 올랐다.
        jdbcTemplate.update("""
                update subway_leg_binding set version = 1 where journey_leg_id = ?
                """, rideLegId);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("SKIPPED");
        verify(pushPort, never()).send(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("잠정 고정도 발송한다 — 잠금화면은 잠정부터 따라온다(2026-09-29 결정)")
    void provisionalBindingIsSent() {
        registerTokens();
        jdbcTemplate.update("""
                update subway_leg_binding set status = 'PROVISIONAL' where journey_leg_id = ?
                """, rideLegId);
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("고정이 접히면 발송하지 않는다")
    void abandonedBindingIsNotSent() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        jdbcTemplate.update("""
                update subway_leg_binding set status = 'ABANDONED' where journey_leg_id = ?
                """, rideLegId);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("SKIPPED");
    }

    @Test
    @DisplayName("등록된 Activity 가 없으면 실패가 아니라 SKIPPED 다")
    void noTokenIsNotAFailure() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("SKIPPED");
    }

    // ── 발송 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("두 토큰이 각자 제자리로 나간다 — 기기 주소와 Activity 주소")
    void bothTokensAreDispatched() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayService.runOnce();

        verify(pushPort).send("device-token-1", "activity-token-1", "update",
                apsTimestampOfSequence(1L), payloadOf());
        assertThat(statusOfSequence(1L)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("provider 가 UNREGISTERED 라고 답하면 그 Activity 주소를 끈다")
    void unregisteredDisablesTheActivityToken() {
        registerTokens();
        given(pushPort.send(anyString(), anyString(), anyString(), anyLong(), any()))
                .willReturn(LiveActivityPushPort.PushResult.failed("UNREGISTERED"));
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayService.runOnce();

        assertThat(jdbcTemplate.queryForObject("""
                select enabled from subway_live_activity_token where journey_id = ?
                """, Boolean.class, journeyId)).isFalse();
    }

    @Test
    @DisplayName("실패해도 최신이면 재시도로 되돌아간다")
    void latestFailureIsRetried() {
        registerTokens();
        given(pushPort.send(anyString(), anyString(), anyString(), anyLong(), any()))
                .willReturn(LiveActivityPushPort.PushResult.failed("UNAVAILABLE"));
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("PENDING");
        assertThat(attemptCountOfSequence(1L)).isEqualTo(1);
    }

    @Test
    @DisplayName("시도를 다 쓰면 FAILED 로 접는다 — 무한히 돌지 않는다")
    void retriesAreBounded() {
        registerTokens();
        given(pushPort.send(anyString(), anyString(), anyString(), anyLong(), any()))
                .willReturn(LiveActivityPushPort.PushResult.failed("UNAVAILABLE"));
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        for (int attempt = 0; attempt < 5; attempt++) {
            relayService.runOnce();
        }

        assertThat(statusOfSequence(1L)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("쌓인 사이 여정이 다음 단계로 넘어가면 발송하지 않는다 — 지하철 화면이 다음 도보 화면을 덮는다")
    void rowAfterLegLeftIsNotSent() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex + 1, journeyId);

        relayService.runOnce();

        assertThat(statusOfSequence(1L)).isEqualTo("SKIPPED");
        assertThat(jdbcTemplate.queryForObject("""
                select last_failure_reason from subway_ring_push_outbox where journey_id = ? and sequence = 1
                """, String.class, journeyId)).isEqualTo("leg_left");
        verify(pushPort, never()).send(anyString(), anyString(), anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("대기(앞 단계)와 탑승 중에는 발송한다 — 고르고 기다리는 동안의 링(#304)")
    void waitingAndRidingAreSent() {
        registerTokens();
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex - 1, journeyId);
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        relayService.runOnce();
        assertThat(statusOfSequence(1L)).isEqualTo("SENT");

        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex, journeyId);
        enqueueService.enqueueIfChanged(ring(1, "0.4500"), ring(2, "0.0000"), rideLeg);
        relayService.runOnce();
        assertThat(statusOfSequence(2L)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("링 대상은 그 탑승의 앞 대기·탑승 중일 때만 — 내린 뒤에는 갱신하지 않는다")
    void ringTargetsFollowTheCurrentLeg() {
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex - 1, journeyId);
        assertThat(ringTargetLegIds()).contains(rideLegId);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex, journeyId);
        assertThat(ringTargetLegIds()).contains(rideLegId);
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex + 1, journeyId);
        assertThat(ringTargetLegIds()).doesNotContain(rideLegId);
    }

    @Test
    @DisplayName("종료 프레임은 단계 가드와 무관하게 나간다 — 하차 뒤 끝나도 잠금화면을 치운다")
    void terminalIsSentEvenAfterLegLeft() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        relayService.runOnce();
        jdbcTemplate.update("update journey_session set current_leg_index = ? where id = ?",
                rideLegIndex + 1, journeyId);

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));
        relayService.runOnce();

        assertThat(jdbcTemplate.queryForObject("""
                select status from subway_ring_push_outbox where journey_id = ? and event = 'end'
                """, String.class, journeyId)).isEqualTo("SENT");
    }

    private List<Long> ringTargetLegIds() {
        return bindingRepository.findRingTargets(500).stream()
                .map(watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding::getJourneyLegId).toList();
    }

    // ── 종료 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("여정이 끝나면 event=end 행이 생긴다")
    void journeyEndCreatesTerminalRow() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        assertThat(jdbcTemplate.queryForObject("""
                select event from subway_ring_push_outbox where journey_id = ?
                 order by sequence desc limit 1
                """, String.class, journeyId)).isEqualTo("end");
    }

    @Test
    @DisplayName("종료 행은 마지막 화면을 그대로 쓴다 — 진행률을 1.0 으로 지어내지 않는다")
    void terminalKeepsTheLastScreen() throws Exception {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        JsonNode terminal = payloadOfSequence(2L);
        assertThat(terminal.get("name").asText()).isEqualTo("NavigationLiveActivity");
        assertThat(terminal.get("props").isTextual())
                .as("종료도 props 는 JSON 문자열이어야 한다 — 객체로 펴지면 앱이 무시한다")
                .isTrue();

        // 문자열 비교가 아니라 값으로 본다. jsonb 는 키 순서를 보존하지 않고 수치 표기도
        // 원본 그대로 유지해(0.4500), 직렬화 문자열은 만드는 쪽마다 다르다.
        JsonNode props = propsOf(terminal);
        // 1.0 이 아니다. 종료는 하차로만 오는 것이 아니라 lease 만료·갈아타기로도 온다.
        assertThat(props.get("segmentProgress").decimalValue()).isEqualByComparingTo("0.45");
        assertThat(props.get("nextStationName").asText()).isEqualTo("역1");
        assertThat(props.get("phase").asText()).isEqualTo("RIDE_SUBWAY");
    }

    @Test
    @DisplayName("종료 프레임의 updatedAt 은 새로 찍힌다 — 마지막 진행 프레임보다 나중이다")
    void terminalRefreshesUpdatedAt() throws Exception {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        long lastUpdatedAt = updatedAtOf(payloadOfSequence(1L));

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        assertThat(updatedAtOf(payloadOfSequence(2L))).isGreaterThan(lastUpdatedAt);
    }

    @Test
    @DisplayName("종료가 이미 있으면 두 번 만들지 않는다 — 접기 경로가 둘 이상 지나가도")
    void terminalIsMadeOnce() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from subway_ring_push_outbox
                 where journey_id = ? and event = 'end'
                """, Integer.class, journeyId)).isEqualTo(1);
    }

    @Test
    @DisplayName("보낸 적 없는 여정에는 종료 행을 만들지 않는다 — 지울 화면이 없다")
    void nothingShownMeansNothingToDismiss() {
        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        assertThat(rowCount()).isZero();
    }

    @Test
    @DisplayName("종료를 보낸 뒤에 토큰을 정리한다 — 먼저 지우면 보낼 주소가 없다")
    void tokensAreReleasedAfterTheTerminalPush() {
        registerTokens();
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);
        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpen(journeyId, SubwayBindingAbandonReason.ENDED));

        relayService.runOnce();

        assertThat(statusOfSequence(2L)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from subway_live_activity_token
                 where journey_id = ? and deleted_at is null
                """, Integer.class, journeyId)).isZero();
    }

    @Test
    @DisplayName("재탐색은 종료를 만들지 않는다 — Activity 는 여정 단위라 이어진다")
    void rerouteDoesNotEndTheActivity() {
        enqueueService.enqueueIfChanged(null, ring(1, "0.4500"), rideLeg);

        transactionTemplate.executeWithoutResult(status ->
                lifecycleService.abandonOpenOfRevision(journeyId, 1,
                        SubwayBindingAbandonReason.SUPERSEDED_BY_REVISION));

        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from subway_ring_push_outbox
                 where journey_id = ? and event = 'end'
                """, Integer.class, journeyId)).isZero();
    }

    // ── 도우미 ──────────────────────────────────────────────────────────────

    private void registerTokens() {
        Long pushTokenId = jdbcTemplate.queryForObject("""
                insert into user_push_token
                    (created_at, updated_at, user_id, platform, provider, token, environment,
                     enabled, last_seen_at)
                values (now(), now(), ?, 'IOS', 'FCM', 'device-token-1', 'PRODUCTION', true, now())
                returning id
                """, Long.class, userId);
        jdbcTemplate.update("""
                insert into subway_live_activity_token
                    (created_at, updated_at, user_id, journey_id, activity_token, push_token_id,
                     environment, enabled, last_seen_at)
                values (now(), now(), ?, ?, 'activity-token-1', ?, 'PRODUCTION', true, now())
                """, userId, journeyId, pushTokenId);
    }

    private void insertBinding(String status, int version) {
        jdbcTemplate.update("""
                insert into subway_leg_binding
                    (created_at, updated_at, journey_leg_id, journey_id, route_revision, leg_index,
                     status, train_no, train_service_date, train_line_id, train_direction,
                     version, reset_generation, last_movement_seq)
                values (now(), now(), ?, ?, 1, ?, ?, '2233', ?, '1002', '내선', ?, 0, 1)
                """, rideLegId, journeyId, rideLegIndex, status,
                java.sql.Date.valueOf(SERVICE_DATE), version);
    }

    private SubwayRingState ring(int stationSequence, String progress) {
        return ringOfRevision(1, stationSequence, progress);
    }

    private SubwayRingState ringOfRevision(int routeRevision, int stationSequence,
                                           String progress) {
        return new SubwayRingState(journeyId, routeRevision, rideLegIndex, 0,
                stationSequence, "100200022" + stationSequence, "역" + stationSequence, "하차역", 2,
                new BigDecimal(progress), T0, new BigDecimal("0.90"),
                T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }

    private JsonNode propsOf(JsonNode contentState) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(contentState.get("props").asText());
    }

    private long updatedAtOf(JsonNode contentState) throws Exception {
        return propsOf(contentState).get("updatedAt").asLong();
    }

    private SubwayRingPushOutbox rowOfSequence(long sequence) {
        Long id = jdbcTemplate.queryForObject("""
                select id from subway_ring_push_outbox where journey_id = ? and sequence = ?
                """, Long.class, journeyId, sequence);
        return outboxRepository.findById(id).orElseThrow();
    }

    private Integer rowCount() {
        return jdbcTemplate.queryForObject(
                "select count(*) from subway_ring_push_outbox where journey_id = ?",
                Integer.class, journeyId);
    }

    private List<Map.Entry<Long, String>> statusesBySequence() {
        return jdbcTemplate.query("""
                select sequence, status from subway_ring_push_outbox
                 where journey_id = ? order by sequence
                """, (rs, i) -> Map.entry(rs.getLong("sequence"), rs.getString("status")),
                journeyId);
    }

    private String statusOfSequence(long sequence) {
        return jdbcTemplate.queryForObject("""
                select status from subway_ring_push_outbox where journey_id = ? and sequence = ?
                """, String.class, journeyId, sequence);
    }

    private Integer attemptCountOfSequence(long sequence) {
        return jdbcTemplate.queryForObject("""
                select attempt_count from subway_ring_push_outbox
                 where journey_id = ? and sequence = ?
                """, Integer.class, journeyId, sequence);
    }

    private long apsTimestampOfSequence(long sequence) {
        return jdbcTemplate.queryForObject("""
                select aps_timestamp from subway_ring_push_outbox
                 where journey_id = ? and sequence = ?
                """, Long.class, journeyId, sequence);
    }

    private JsonNode payloadOf() {
        return payloadOfSequence(1L);
    }

    private JsonNode payloadOfSequence(long sequence) {
        String raw = jdbcTemplate.queryForObject("""
                select payload::text from subway_ring_push_outbox
                 where journey_id = ? and sequence = ?
                """, String.class, journeyId, sequence);
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("payload 를 읽을 수 없다", e);
        }
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
