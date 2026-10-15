package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.jdbc.Sql;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import watoo.grd.nextroute.application.journey.service.JourneyNotificationOutboxStateService;
import watoo.grd.nextroute.application.journey.service.RerouteAlertTxService;
import watoo.grd.nextroute.application.journey.service.RerouteBoardPushPolicy;
import watoo.grd.nextroute.application.journey.service.RerouteBoardPushTxService;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 울릴 권리(#428 PR-1b) 계약. 경로 버전마다 위험·재탐색을 합쳐 소리 나는 알림은 한 번이다.
 *
 * <p>"울림 1번"은 알림 행 개수가 아니라 <b>살아 있는(취소되지 않은) ALERTING 행</b>으로 잰다 — 대체된 행은
 * 나가지 않으므로 사용자를 울리지 않는다.
 */
@DisplayName("울릴 권리 — 경로 버전당 울림 한 번")
@Import({RerouteBoardPushTxService.class, RerouteAlertTxService.class,
        JourneyNotificationOutboxStateService.class, JourneyRerouteMetrics.class})
@Sql(scripts = {
        "classpath:db/it/journey-reset.sql",
        "classpath:db/migration/V35__create_journey.sql",
        "classpath:db/migration/V36__add_journey_transit_resolution.sql",
        "classpath:db/migration/V37__align_journey_navigation_contract.sql",
        "classpath:db/migration/V38__add_journey_last_progress_id.sql",
        "classpath:db/migration/V39__create_user_push_token.sql",
        "classpath:db/migration/V40__create_journey_notification.sql",
        "classpath:db/migration/V70__journey_notification_priority.sql",
        "classpath:db/migration/V42__create_reroute_proposal.sql",
        "classpath:db/migration/V43__add_reroute_proposal_accepted_candidate.sql",
        "classpath:db/migration/V50__add_reroute_proposal_origin.sql",
        "classpath:db/migration/V51__reroute_proposal_journey_active_unique.sql",
        "classpath:db/migration/V52__drop_reroute_proposal_transfer_index.sql",
        "classpath:db/migration/V53__add_reroute_alert_state.sql",
        "classpath:db/migration/V54__add_reroute_proposal_alerted.sql",
        "classpath:db/migration/V57__reroute_board.sql",
        "classpath:db/migration/V59__journey_session_kind.sql",
        "classpath:db/migration/V63__journey_leg_guidance_info.sql",
        "classpath:db/migration/V65__journey_leg_alight_door_side.sql",
        "classpath:db/migration/V66__journey_session_evaluation_urgent.sql",
        "classpath:db/migration/V67__journey_reevaluation_trigger.sql",
        "classpath:db/migration/V69__journey_session_alert_claim.sql"
},
        executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class JourneyAlertBudgetIT extends AbstractJourneyNotificationIT {

    private static final Instant T0 = Instant.parse("2026-08-06T00:00:00Z");

    @Autowired RerouteBoardPushTxService boardPushTxService;
    @Autowired RerouteAlertTxService rerouteAlertTxService;
    @Autowired JourneyNotificationOutboxStateService stateService;
    @Autowired JourneyRerouteProperties rerouteProperties;
    @Autowired PlatformTransactionManager transactionManager;

    @BeforeEach
    void enableFatigue() {
        properties.getFatigue().setEnabled(true);
        setBoardMode(false);
    }

    @AfterEach
    void cleanUp() {
        properties.getFatigue().setEnabled(false);
        setBoardMode(false);
        // 부모의 준비가 journey_session 을 지우는데, 제안 행이 남아 있으면 FK 에 걸린다.
        jdbcTemplate.update("delete from reroute_proposal");
        // 단계·노선 후보도 같다. 단계가 후보를 가리키는 순환 참조라 먼저 끊는다.
        jdbcTemplate.update("update journey_leg set selected_route_option_id = null");
        jdbcTemplate.update("delete from journey_leg_route_option");
        jdbcTemplate.update("delete from journey_leg");
    }

    // ── 승격·합치기 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("창 안에서 빠듯→놓침이면 살아 있는 울림은 놓침 하나이고 권리도 놓침으로 옮겨 간다")
    void atRiskThenMissedInsideHoldLeavesOneAlertingMissed() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("checkpoint")).isEqualTo("TRANSFER_RISK_AT_RISK");
        assertThat(rows.get(0).get("status")).isEqualTo("CANCELED");
        assertThat(rows.get(0).get("last_failure_reason")).isEqualTo("superseded");
        assertThat(liveAlerting()).singleElement()
                .satisfies(row -> assertThat(row.get("checkpoint")).isEqualTo("TRANSFER_RISK_MISSED"));
        assertThat(claimKey()).isEqualTo(riskKey(0, "TRANSFER_RISK_MISSED"));
    }

    @Test
    @DisplayName("한 평가에서 환승 두 개가 나빠지면 심한 쪽 하나만 울리고 나머지는 조용하다")
    void twoTransfersWorsenTogether() {
        addTransfer(1, 1, 3, 4);
        evaluate(new TransferAssessmentBatch(List.of(
                assessment(0, TransferStatus.AT_RISK, 2, T0.plusSeconds(60)),
                assessment(1, TransferStatus.MISSED, 4, T0.minusSeconds(120))), Map.of(), false), 0,
                NotificationChannel.PUSH);

        assertThat(liveAlerting()).singleElement()
                .satisfies(row -> assertThat(row.get("scope_id")).isEqualTo(1));
        assertThat(delivery("TRANSFER_RISK_AT_RISK")).isEqualTo("QUIET");
        assertThat(claimKey()).isEqualTo(riskKey(1, "TRANSFER_RISK_MISSED"));
    }

    @Test
    @DisplayName("채널이 둘이어도 판정은 한 번 — 두 채널 행이 같은 울림이다")
    void twoChannelsShareOneDecision() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH, NotificationChannel.TOSS);

        assertThat(notifications()).hasSize(2)
                .allSatisfy(row -> assertThat(payloadDelivery(row)).isEqualTo("ALERTING"));
    }

    @Test
    @DisplayName("이미 나간 울림 뒤의 악화는 조용하고, 보류 없이 바로 나가며, 접근 알림을 지우지 않는다")
    void laterWorseningIsQuietWithoutHoldAndKeepsApproach() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        jdbcTemplate.update("update journey_notification set status = 'SENT'");
        insertPendingApproach();

        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        Map<String, Object> missed = row("TRANSFER_RISK_MISSED");
        assertThat(payloadDelivery(missed)).isEqualTo("QUIET");
        assertThat(scheduledAt(missed)).as("조용한 갱신은 늦출 이유가 없다").isEqualTo(T0);
        assertThat(row("TRANSFER_TARGET_APPROACHING").get("status"))
                .as("소리 없는 갱신이 고개를 들어야 할 알림을 없애면 안 된다")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("울리는 위험 알림은 대기 중인 접근 알림을 취소한다(정책 이전과 같은 규칙)")
    void alertingRiskCancelsPendingApproach() {
        insertPendingApproach();

        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(row("TRANSFER_TARGET_APPROACHING").get("status")).isEqualTo("CANCELED");
        assertThat(payloadDelivery(row("TRANSFER_RISK_MISSED"))).isEqualTo("ALERTING");
    }

    // ── 보드 push 와 재탐색 ─────────────────────────────────────────────────────

    @Test
    @DisplayName("보드 push 가 대기 중인 위험 알림을 대체하면 권리를 물려받아 울린다")
    void boardPushInheritsPendingRiskClaim() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        long proposalId = insertBoard();

        assertThat(publishBoard(proposalId, 0)).isTrue();

        assertThat(row("TRANSFER_RISK_AT_RISK").get("status")).isEqualTo("CANCELED");
        assertThat(row("TRANSFER_RISK_AT_RISK").get("last_failure_reason")).isEqualTo("superseded-by-reroute");
        assertThat(liveAlerting()).singleElement()
                .satisfies(row -> assertThat(row.get("checkpoint")).isEqualTo("REROUTE_SUGGESTED"));
        assertThat(claimKey()).endsWith(":REROUTE_SUGGESTED:p1");
    }

    @Test
    @DisplayName("재탐색이 이미 울렸으면 뒤의 위험 악화는 조용한 알림조차 만들지 않는다(quiet-dominated)")
    void riskAfterRerouteClaimIsDominated() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        publishBoard(insertBoard(), 0);

        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(notifications()).noneMatch(row -> "TRANSFER_RISK_MISSED".equals(row.get("checkpoint")));
        assertThat(marker()).as("마커는 쓴다 — 안 쓰면 매 평가 같은 판단을 되풀이한다").isEqualTo("MISSED");
    }

    @Test
    @DisplayName("같은 경로 버전의 두 번째 보드 push 는 조용하다")
    void secondBoardPushIsQuiet() {
        long proposalId = insertBoard();
        publishBoard(proposalId, 0);
        publishBoard(proposalId, 1);

        assertThat(notifications()).extracting(this::payloadDelivery).containsExactly("ALERTING", "QUIET");
    }

    @Test
    @DisplayName("재탐색 알림도 같은 권리를 쓴다 — 울린 뒤의 다음 제안은 조용하다")
    void rerouteAlertUsesSameClaim() {
        assertThat(rerouteAlertTxService.alert(view(101L), 120, List.of(NotificationChannel.PUSH), T0)).isTrue();
        assertThat(rerouteAlertTxService.alert(view(102L), 120, List.of(NotificationChannel.PUSH), T0)).isTrue();

        assertThat(notifications()).extracting(this::payloadDelivery).containsExactly("ALERTING", "QUIET");
        assertThat(claimKey()).isEqualTo(journey.getId() + ":1:JOURNEY:101:REROUTE_SUGGESTED");
    }

    @RepeatedTest(5)
    @DisplayName("평가기의 위험 알림과 보드 push 가 동시에 돌아도 살아 있는 울림은 하나다")
    void evaluatorAndBoardConcurrently() throws Exception {
        long proposalId = insertBoard();
        CyclicBarrier start = new CyclicBarrier(2);
        CompletableFuture<Void> evaluator = CompletableFuture.runAsync(() -> {
            await(start);
            evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        });
        CompletableFuture<Void> board = CompletableFuture.runAsync(() -> {
            await(start);
            publishBoard(proposalId, 0);
        });
        CompletableFuture.allOf(evaluator, board).get(30, TimeUnit.SECONDS);

        assertThat(liveAlerting()).hasSize(1);
        String alertingKey = (String) liveAlerting().get(0).get("logical_checkpoint_key");
        assertThat(claimKey()).isEqualTo(alertingKey);
    }

    // ── 돌려받기·경로 버전 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("울림 알림이 발송 실패로 끝나면 권리를 돌려받아 다음 악화가 울린다")
    void failedAlertingReleasesClaim() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        long id = singleId();
        List<ClaimedNotification> claimed = stateService.claimBatch(T0.plusSeconds(60), 10);
        assertThat(claimed).extracting(ClaimedNotification::id).containsExactly(id);

        stateService.markPermanentFailure(claimed.get(0), "invalid-token");

        assertThat(claimKey()).isNull();
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);
        assertThat(payloadDelivery(row("TRANSFER_RISK_MISSED"))).isEqualTo("ALERTING");
    }

    @Test
    @DisplayName("울림 알림이 발송 직전에 만료로 취소돼도 권리를 돌려받는다")
    void expiredAlertingReleasesClaim() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        long id = singleId();
        ClaimedNotification claim = stateService.claimBatch(T0.plusSeconds(60), 10).get(0);
        jdbcTemplate.update("update journey_notification set expires_at = ? where id = ?",
                Timestamp.from(T0.minusSeconds(1)), id);

        assertThat(stateService.loadTask(claim)).isEmpty();

        assertThat(claimKey()).isNull();
    }

    @Test
    @DisplayName("늦은 기록(회수 뒤 재claim)은 울릴 권리를 건드리지 않는다(#451)")
    void staleWriteKeepsAlertClaim() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        long id = singleId();
        String key = claimKey();
        ClaimedNotification old = stateService.claimBatch(T0.plusSeconds(60), 10).get(0);
        jdbcTemplate.update("update journey_notification set processing_started_at = now() - interval '1 hour'"
                + " where id = ?", id);
        stateService.reclaim(T0.plusSeconds(60));
        ClaimedNotification fresh = stateService.claimBatch(T0.plusSeconds(60), 10).get(0);

        assertThatThrownBy(() -> stateService.markPermanentFailure(old, "invalid-token"))
                .isInstanceOf(StaleClaimException.class);
        assertThatThrownBy(() -> stateService.markTransientFailure(old, "5xx"))
                .isInstanceOf(StaleClaimException.class);

        assertThat(claimKey()).as("반납이 롤백돼야 한다 — 비면 같은 경로 버전이 두 번 울린다").isEqualTo(key);
        assertThat(status(id)).isEqualTo("PROCESSING");
        stateService.markSent(fresh);
        assertThat(status(id)).isEqualTo("SENT");
        assertThat(claimKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("두 트랜잭션 경합: 회수·재claim 이 행을 잡은 사이 옛 워커의 실패 기록은 0행이고 권리 반납도 되돌린다(#451)")
    void racingStaleWriteRollsBackClaimRelease() throws Exception {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        long id = singleId();
        String key = claimKey();
        ClaimedNotification old = stateService.claimBatch(T0.plusSeconds(60), 10).get(0);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch rivalHoldsRow = new CountDownLatch(1);
        CountDownLatch rivalMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> rival = pool.submit(() -> tx.execute(s -> {
                jdbcTemplate.update("update journey_notification set processing_started_at = now() - interval '1 hour'"
                        + " where id = ?", id);
                stateService.reclaim(T0.plusSeconds(60));
                int token = stateService.claimBatch(T0.plusSeconds(60), 10).get(0).token();
                rivalHoldsRow.countDown();
                try {
                    rivalMayCommit.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return token;
            }));
            assertThat(rivalHoldsRow.await(10, TimeUnit.SECONDS)).isTrue();
            // 옛 워커: 커밋된 상태(PROCESSING, 옛 증서)를 읽고 권리를 반납한 뒤 알림 행 잠금에서 기다린다.
            Future<?> late = pool.submit(() -> stateService.markPermanentFailure(old, "invalid-token"));
            awaitLockWait();
            rivalMayCommit.countDown();

            assertThat(rival.get(10, TimeUnit.SECONDS)).isEqualTo(old.token() + 1);
            assertThatThrownBy(() -> late.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(StaleClaimException.class);
        } finally {
            rivalMayCommit.countDown();
            pool.shutdownNow();
        }
        assertThat(status(id)).isEqualTo("PROCESSING");
        assertThat(claimKey()).isEqualTo(key);
    }

    /** 다른 세션이 행 잠금을 기다리기 시작할 때까지(최대 5초). 못 보면 그냥 진행한다 — 경합 없이도 결론은 같다. */
    private void awaitLockWait() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject(
                    "select count(*) from pg_stat_activity where wait_event_type = 'Lock'", Integer.class);
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(20);
        }
    }

    @Test
    @DisplayName("조용한 알림이 실패해도 권리는 그대로다")
    void failedQuietKeepsClaim() {
        addTransfer(1, 1, 3, 4);
        evaluate(new TransferAssessmentBatch(List.of(
                assessment(0, TransferStatus.AT_RISK, 2, T0.plusSeconds(60)),
                assessment(1, TransferStatus.MISSED, 4, T0.minusSeconds(120))), Map.of(), false), 0,
                NotificationChannel.PUSH);
        long quietId = ((Number) row("TRANSFER_RISK_AT_RISK").get("id")).longValue();
        ClaimedNotification quiet = stateService.claimBatch(T0.plusSeconds(60), 10).stream()
                .filter(c -> c.id() == quietId).findFirst().orElseThrow();

        stateService.markPermanentFailure(quiet, "invalid-token");

        assertThat(claimKey()).isEqualTo(riskKey(1, "TRANSFER_RISK_MISSED"));
    }

    @Test
    @DisplayName("경로 버전이 바뀌면 새로 한 번 울릴 수 있다")
    void newRouteRevisionAllowsNewAlert() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        jdbcTemplate.update("update journey_notification set status = 'SENT'");
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?", journey.getId());
        transferRepository.saveAndFlush(TransferConnection.builder()
                .journeyId(journey.getId()).routeRevision(2).transferIndex(0)
                .fromLegIndex(1).walkLegIndex(null).toLegIndex(2)
                .status(TransferStatus.UNKNOWN).build());

        evaluateRevision(2, TransferStatus.AT_RISK);

        assertThat(notifications()).filteredOn(row -> ((Number) row.get("route_revision")).intValue() == 2)
                .singleElement()
                .satisfies(row -> assertThat(payloadDelivery(row)).isEqualTo("ALERTING"));
        assertThat(claimKey()).startsWith(journey.getId() + ":2:");
    }

    @Test
    @DisplayName("회복이 대기 중인 울림을 거두면 권리를 돌려받고, 같은 상태로 재악화하면 중복이라 행 없이 권리도 반납된다")
    void recoveryReleasesClaimAndDuplicateReleasesAgain() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);
        assertThat(claimKey()).as("거둔 알림은 나가지 않았다 — 사용자는 한 번도 안 울렸다").isNull();

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        assertThat(notifications()).as("기존 함정: 같은 발송 키라 ON CONFLICT 로 행이 안 생긴다").hasSize(1);
        assertThat(claimKey()).as("울릴 알림이 없는데 권리를 쥐면 이 경로 버전은 다시 못 울린다").isNull();
    }

    // ── 합치기 창 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("빠듯 울림은 수집 1번 + 여유 2초 = 17초 뒤에 나간다")
    void atRiskHold() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_AT_RISK"))).isEqualTo(T0.plusSeconds(17));
    }

    @Test
    @DisplayName("보드 모드의 놓침 울림은 대안을 기다려 수집 2번 + 2초 = 32초 뒤에 나간다")
    void missedHoldInBoardMode() {
        setBoardMode(true);

        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_MISSED"))).isEqualTo(T0.plusSeconds(32));
    }

    @Test
    @DisplayName("보류 중 빠듯이 놓침으로 대체돼도 첫 판정의 보류 끝(17초)보다 늦게 울리지 않는다")
    void replacementKeepsFirstHoldDeadline() {
        setBoardMode(true);
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_MISSED")))
                .as("보드 모드 놓침의 자체 보류(32초)를 처음부터 다시 세면 첫 울림이 최대 49초까지 밀린다")
                .isEqualTo(T0.plusSeconds(17));
    }

    @Test
    @DisplayName("보드 모드가 아닌 놓침 울림은 수집 1번 + 2초 = 17초")
    void missedHoldWithoutBoardMode() {
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_MISSED"))).isEqualTo(T0.plusSeconds(17));
    }

    @Test
    @DisplayName("다음 차가 행동 여유(30초) 안에 오면 빠듯 울림은 즉시 나간다")
    void atRiskImmediateWhenNextVehicleTooSoon() {
        evaluate(new TransferAssessmentBatch(List.of(
                assessment(0, TransferStatus.AT_RISK, 2, T0.plusSeconds(25))), Map.of(), false), 0,
                NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_AT_RISK"))).isEqualTo(T0);
    }

    @Test
    @DisplayName("보류는 다음 차 도착 − 30초를 넘지 않는다")
    void atRiskHoldCappedByNextVehicle() {
        evaluate(new TransferAssessmentBatch(List.of(
                assessment(0, TransferStatus.AT_RISK, 2, T0.plusSeconds(40))), Map.of(), false), 0,
                NotificationChannel.PUSH);

        assertThat(scheduledAt(row("TRANSFER_RISK_AT_RISK"))).isEqualTo(T0.plusSeconds(10));
    }

    // ── 꺼짐 ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("꺼져 있으면 payload 에 delivery 가 없고 권리 칸도 건드리지 않는다")
    void disabledLeavesNoTrace() {
        properties.getFatigue().setEnabled(false);

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(notifications()).allSatisfy(row -> assertThat(payloadDelivery(row)).isNull());
        assertThat(scheduledAt(row("TRANSFER_RISK_MISSED"))).isEqualTo(T0);
        assertThat(jdbcTemplate.queryForObject(
                "select alert_claim_revision from journey_session where id = ?", Integer.class, journey.getId()))
                .isNull();
    }

    // ── 문구 재료(#428 PR-2) ──────────────────────────────────────────────────

    @Test
    @DisplayName("위험 payload 에 갈아탈 차(toLegIndex 단계의 고른 후보) 노선 이름이 실린다")
    void riskPayloadCarriesTargetRouteName() {
        insertWaitLeg(2, "2호선", true);

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(payloadText(row("TRANSFER_RISK_AT_RISK"), "routeName")).isEqualTo("2호선");
    }

    @Test
    @DisplayName("갈아탈 단계를 모르거나 후보가 여럿인데 고르지 않았으면 노선 이름을 싣지 않는다")
    void riskPayloadOmitsRouteNameWhenUnknown() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        assertThat(payloadText(row("TRANSFER_RISK_AT_RISK"), "routeName")).isNull();

        long legId = insertWaitLeg(2, "146", false);
        insertOption(legId, 1, "360");
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);
        assertThat(payloadText(row("TRANSFER_RISK_MISSED"), "routeName"))
                .as("여러 후보 중 아무거나 고르면 문구의 노선과 도착 시각이 다른 차를 가리킬 수 있다")
                .isNull();
    }

    @Test
    @DisplayName("후보가 하나뿐이면 고르지 않았어도 그 이름을 쓴다")
    void riskPayloadUsesSoleOption() {
        insertWaitLeg(2, "146", false);

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(payloadText(row("TRANSFER_RISK_AT_RISK"), "routeName")).isEqualTo("146");
    }

    @Test
    @DisplayName("꺼져 있으면 노선 이름도 싣지 않는다(정책 이전과 같은 바이트)")
    void disabledOmitsRouteName() {
        properties.getFatigue().setEnabled(false);
        insertWaitLeg(2, "2호선", true);

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(payloadText(row("TRANSFER_RISK_AT_RISK"), "routeName")).isNull();
    }

    @Test
    @DisplayName("보드 push 가 놓침을 대신하면 payload 에 replacedRisk=MISSED")
    void boardPushRecordsReplacedMissed() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        publishBoard(insertBoard(), 0);

        assertThat(payloadText(row("REROUTE_SUGGESTED"), "replacedRisk")).isEqualTo("MISSED");
    }

    @Test
    @DisplayName("보드 push 가 빠듯을 대신하면 replacedRisk=AT_RISK, 대신한 게 없으면 칸이 없다")
    void boardPushRecordsReplacedAtRiskOrNothing() {
        long proposalId = insertBoard();
        publishBoard(proposalId, 0);
        assertThat(payloadText(row("REROUTE_SUGGESTED"), "replacedRisk")).isNull();

        jdbcTemplate.update("delete from journey_notification");
        jdbcTemplate.update("update journey_session set alert_claim_revision = null, alert_claim_key = null");
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        publishBoard(proposalId, 1);

        assertThat(notifications()).filteredOn(r -> "REROUTE_SUGGESTED".equals(r.get("checkpoint")))
                .singleElement()
                .satisfies(r -> assertThat(payloadText(r, "replacedRisk")).isEqualTo("AT_RISK"));
    }

    // ── 도우미 ────────────────────────────────────────────────────────────────

    /** 대기 단계 하나와 노선 후보 하나. {@code select} 면 단계가 그 후보를 고른 상태로 둔다. */
    private long insertWaitLeg(int legIndex, String routeName, boolean select) {
        long legId = jdbcTemplate.queryForObject("""
                insert into journey_leg (created_at, updated_at, journey_id, route_revision, leg_index, type, state)
                values (now(), now(), ?, 1, ?, 'WAIT_SUBWAY', 'PENDING')
                returning id
                """, Long.class, journey.getId(), legIndex);
        long optionId = insertOption(legId, 0, routeName);
        if (select) {
            jdbcTemplate.update("update journey_leg set selected_route_option_id = ? where id = ?", optionId, legId);
        }
        return legId;
    }

    private long insertOption(long legId, int optionIndex, String routeName) {
        return jdbcTemplate.queryForObject("""
                insert into journey_leg_route_option (created_at, updated_at, journey_leg_id, option_index,
                    lane_index, route_name)
                values (now(), now(), ?, ?, 0, ?)
                returning id
                """, Long.class, legId, optionIndex, routeName);
    }

    private String payloadText(Map<String, Object> row, String field) {
        return jdbcTemplate.queryForObject(
                "select payload ->> ? from journey_notification where id = ?", String.class,
                field, ((Number) row.get("id")).longValue());
    }


    private void setBoardMode(boolean on) {
        rerouteProperties.setEnabled(on);
        rerouteProperties.getBoard().setEnabled(on);
        rerouteProperties.getAlert().setEnabled(on);
    }

    private void evaluateRevision(int revision, TransferStatus status) {
        jdbcTemplate.update("update journey_session set next_evaluation_at = ? where id = ?",
                Timestamp.from(LEASE_UNTIL), journey.getId());
        scheduleWriter.finish(new JourneyDueClaim(journey.getId(), LEASE_UNTIL, LEASE_UNTIL),
                new JourneyEvaluationOutcome(journey.getId(), revision, batchOf(status), true),
                LEASE_UNTIL.plusSeconds(30), false, 10L, LEASE_UNTIL,
                new NotificationContext(userId, 0, List.of(NotificationChannel.PUSH)));
    }

    private TransferRiskAssessment assessment(int transferIndex, TransferStatus status, int toLegIndex,
                                              Instant nextBusArrivalAt) {
        long slack = nextBusArrivalAt.getEpochSecond() - T0.getEpochSecond();
        return new TransferRiskAssessment(transferIndex, toLegIndex - 1, toLegIndex, status, T0, T0,
                nextBusArrivalAt, slack, CurrentLegArrival.Quality.GOOD, T0,
                CurrentLegArrival.Source.REALTIME_ARRIVAL, null);
    }

    private void addTransfer(int transferIndex, int fromLegIndex, int walkLegIndex, int toLegIndex) {
        transferRepository.saveAndFlush(TransferConnection.builder()
                .journeyId(journey.getId()).routeRevision(1).transferIndex(transferIndex)
                .fromLegIndex(fromLegIndex).walkLegIndex(walkLegIndex).toLegIndex(toLegIndex)
                .status(TransferStatus.UNKNOWN).build());
    }

    private long insertBoard() {
        return jdbcTemplate.queryForObject("""
                insert into reroute_proposal (created_at, updated_at, journey_id, user_id, route_revision,
                    kind, status, base_command_version, candidates, snapshot_observed_at, expires_at)
                values (now(), now(), ?, ?, 1, 'REMAINING_PATH', 'ACTIVE', 0, '[]'::jsonb, now(),
                    now() + interval '5 minutes')
                returning id
                """, Long.class, journey.getId(), userId);
    }

    private boolean publishBoard(long proposalId, int pushSeq) {
        RerouteProposal board = mock(RerouteProposal.class);
        given(board.getId()).willReturn(proposalId);
        given(board.getPushSeq()).willReturn(pushSeq);
        JourneySession current = sessionRepository.findById(journey.getId()).orElseThrow();
        RerouteBoardSlot slot = new RerouteBoardSlot(RerouteBoardSlot.HERE, null, RerouteBoardSlot.State.HIDDEN,
                null, "route-" + pushSeq, 300, T0, null, T0, null);
        return boardPushTxService.publish(board, current,
                new RerouteBoardPushPolicy.Decision(slot, RerouteBoardPushPolicy.Kind.RISK_WITH_ALTERNATIVE),
                List.of(NotificationChannel.PUSH), Map.of("gainSec", 300), T0);
    }

    private RerouteProposalView view(long proposalId) {
        return new RerouteProposalView(proposalId, journey.getId(), 1, null, RerouteProposalKind.REMAINING_PATH,
                0L, T0, T0.plusSeconds(300), List.of());
    }

    private void insertPendingApproach() {
        String logical = riskKey(0, "TRANSFER_TARGET_APPROACHING");
        jdbcTemplate.update("""
                insert into journey_notification (journey_id, user_id, route_revision, command_version,
                    evaluation_version, scope_type, scope_id, checkpoint, channel, logical_checkpoint_key,
                    delivery_key, status, scheduled_at, expires_at, payload, attempt_count, requeue_count,
                    created_at, updated_at)
                values (?, ?, 1, 1, 0, 'TRANSFER', 0, 'TRANSFER_TARGET_APPROACHING', 'PUSH', ?, ?, 'PENDING',
                    ?, ?, null, 0, 0, now(), now())
                """, journey.getId(), userId, logical, logical + ":PUSH",
                Timestamp.from(T0), Timestamp.from(T0.plusSeconds(120)));
    }

    private static void await(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String riskKey(int transferIndex, String checkpoint) {
        return journey.getId() + ":1:TRANSFER:" + transferIndex + ":" + checkpoint;
    }

    private String claimKey() {
        return jdbcTemplate.queryForObject("select alert_claim_key from journey_session where id = ?",
                String.class, journey.getId());
    }

    private List<Map<String, Object>> liveAlerting() {
        return jdbcTemplate.queryForList("""
                select * from journey_notification
                 where status <> 'CANCELED' and payload ->> 'delivery' = 'ALERTING'
                 order by id
                """);
    }

    private Map<String, Object> row(String checkpoint) {
        return notifications().stream().filter(row -> checkpoint.equals(row.get("checkpoint")))
                .findFirst().orElseThrow(() -> new AssertionError("행 없음: " + checkpoint));
    }

    private String delivery(String checkpoint) {
        return payloadDelivery(row(checkpoint));
    }

    private String payloadDelivery(Map<String, Object> row) {
        return jdbcTemplate.queryForObject(
                "select payload ->> 'delivery' from journey_notification where id = ?", String.class,
                ((Number) row.get("id")).longValue());
    }

    private Instant scheduledAt(Map<String, Object> row) {
        Object value = row.get("scheduled_at");
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        throw new AssertionError("모르는 시각 타입: " + value);
    }

    private String status(long id) {
        return jdbcTemplate.queryForObject("select status from journey_notification where id = ?", String.class, id);
    }

    private long singleId() {
        return ((Number) notifications().get(0).get("id")).longValue();
    }
}
