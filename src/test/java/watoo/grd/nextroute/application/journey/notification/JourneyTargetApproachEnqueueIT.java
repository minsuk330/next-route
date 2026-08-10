package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "갈아탈 차가 곧 온다"(N2)가 언제 쌓이고 언제 안 쌓이는지.
 *
 * <p>이 알림의 배타성 축은 <b>상태가 아니라 단계</b>다. 정류장에 서 있는 사용자에게 여유는
 * {@code 다음 차 도착 - 지금} 이라 차가 창 안에 오면 상태는 거의 항상 {@code AT_RISK} 인데,
 * 그 자리에서 "지금 이동하라"는 위험 알림은 틀린 지시다. 그래서 대기 단계에서는 위험 알림이
 * 자리를 넘기고 접근 알림만 나간다.
 */
@DisplayName("갈아탈 차가 임박하면 접근 알림을 쌓는다")
class JourneyTargetApproachEnqueueIT extends AbstractJourneyNotificationIT {

    @Test
    @DisplayName("대기 단계에서 차가 임박하면 접근 알림 한 건이 쌓인다")
    void enqueuesApproachWhileWaiting() {
        evaluateWaiting(60, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement().satisfies(row -> {
            assertThat(row.get("checkpoint"))
                    .isEqualTo(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING.name());
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("scope_type")).isEqualTo("TRANSFER");
            assertThat(row.get("scope_id")).isEqualTo(0);
        });
    }

    @Test
    @DisplayName("여유가 넉넉해도 차가 임박했으면 쌓는다 — 일찍 도착해 기다리는 경우")
    void enqueuesEvenWhenSlackIsComfortable() {
        // 도착 100초 전인데 여유는 100초 — 대기 중에는 둘이 같은 값이다.
        evaluateWaiting(100, NotificationChannel.PUSH);

        assertThat(notifications()).hasSize(1);
    }

    @Test
    @DisplayName("같은 조건으로 여섯 번 더 평가해도 행은 하나다")
    void repeatedEvaluationDoesNotAccumulate() {
        evaluateWaiting(60, NotificationChannel.PUSH);
        for (int tick = 0; tick < 6; tick++) {
            evaluateWaiting(60, NotificationChannel.PUSH);
        }

        assertThat(notifications())
                .as("마커가 없어도 발송 키의 unique 가 중복을 막는다")
                .hasSize(1);
    }

    @Test
    @DisplayName("중복이라 행이 안 생기면 relay 도 깨우지 않는다")
    void duplicateDoesNotWakeRelay() {
        evaluateWaiting(60, NotificationChannel.PUSH);
        assertThat(relayWaker.await(0)).isTrue();

        evaluateWaiting(60, NotificationChannel.PUSH);

        assertThat(relayWaker.await(0))
                .as("시도를 기준으로 깨우면 임박 창이 열린 내내 아무것도 안 늘어난 채 relay 만 돈다")
                .isFalse();
    }

    @Test
    @DisplayName("아직 앞 차를 타고 있으면 쌓지 않는다")
    void skipsWhileStillRiding() {
        evaluate(batchOf(watoo.grd.nextroute.domain.journey.entity.TransferStatus.CATCHABLE),
                0, NotificationChannel.PUSH);

        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("차가 아직 창 밖이면 쌓지 않는다")
    void skipsWhenBusIsBeyondWindow() {
        evaluateWaiting(121, NotificationChannel.PUSH);

        assertThat(notifications()).isEmpty();
    }

    @Test
    @DisplayName("만료는 다음 차 도착 시각 그대로다 — 바닥을 씌우지 않는다")
    void expiresExactlyAtArrival() {
        evaluateWaiting(60, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement().satisfies(row -> {
            Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
            assertThat(expiresAt)
                    .as("바닥(180초)을 씌우면 차가 떠난 뒤에 '곧 온다'가 나간다")
                    .isEqualTo(Instant.now(clock).plusSeconds(60));
        });
    }

    @Test
    @DisplayName("대기 단계에서는 위험 알림이 자리를 넘긴다 — 접근 알림만 나간다")
    void riskYieldsToApproachWhileWaiting() {
        // 여유 60초 = AT_RISK. 서 있는 사람에게 "지금 이동하라"는 틀린 지시다.
        evaluateWaiting(60, NotificationChannel.PUSH);

        assertThat(checkpoints())
                .containsExactly(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING.name());
        assertThat(marker())
                .as("넘긴 알림은 마커도 남기지 않는다 — MISSED 로 떨어지면 그때는 알려야 한다")
                .isNull();
    }

    @Test
    @DisplayName("대기 중에 놓치면 위험 알림이 나간다 — 접근 알림은 안 나간다")
    void missedStillAlertsWhileWaiting() {
        evaluateWaiting(-30, NotificationChannel.PUSH);

        assertThat(checkpoints())
                .as("기다리던 차를 놓친 사실은 서 있는 사람에게 가장 필요하다")
                .containsExactly(JourneyCheckpoint.TRANSFER_RISK_MISSED.name());
    }

    @Test
    @DisplayName("접근 알림이 쌓인 뒤 놓치면, 아직 안 나간 접근 알림은 취소된다")
    void promotionCancelsPendingApproach() {
        evaluateWaiting(60, NotificationChannel.PUSH);
        evaluateWaiting(-30, NotificationChannel.PUSH);

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("checkpoint"))
                .isEqualTo(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING.name());
        assertThat(rows.get(0).get("status"))
                .as("놓친 마당에 '곧 온다'가 뒤늦게 도착하면 거짓 안심이다")
                .isEqualTo("CANCELED");
        assertThat(rows.get(1).get("checkpoint"))
                .isEqualTo(JourneyCheckpoint.TRANSFER_RISK_MISSED.name());
        assertThat(rows.get(1).get("status")).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("이미 발송 중인 접근 알림은 승격이 건드리지 않는다")
    void promotionDoesNotTouchProcessingApproach() {
        evaluateWaiting(60, NotificationChannel.PUSH);
        jdbcTemplate.update("update journey_notification set status = 'PROCESSING'");

        evaluateWaiting(-30, NotificationChannel.PUSH);

        assertThat(notifications().get(0).get("status"))
                .as("발송 중인 행은 회수할 수 없다. relay 의 발송 직전 재확인이 맡는다")
                .isEqualTo("PROCESSING");
    }

    @Test
    @DisplayName("회복 경로는 접근 알림을 지우지 않는다")
    void recoveryKeepsApproach() {
        // 타는 중에 위험 알림을 내 마커를 남긴다.
        evaluate(watoo.grd.nextroute.domain.journey.entity.TransferStatus.AT_RISK,
                0, NotificationChannel.PUSH);
        assertThat(marker()).isEqualTo("AT_RISK");
        // 그 상태에서 접근 알림 행을 직접 심는다 — 정상 전이로는 만들 수 없는 조합이다.
        jdbcTemplate.update("""
                insert into journey_notification
                    (journey_id, user_id, route_revision, command_version, evaluation_version,
                     scope_type, scope_id, checkpoint, channel,
                     logical_checkpoint_key, delivery_key, status, scheduled_at, expires_at,
                     attempt_count, requeue_count, created_at, updated_at)
                values (?, ?, 1, 1, 1, 'TRANSFER', 0, 'TRANSFER_TARGET_APPROACHING', 'PUSH',
                        'seeded-logical', 'seeded-delivery', 'PENDING', now(), now() + interval '2 minutes',
                        0, 0, now(), now())
                """, journey.getId(), userId);

        // 여유가 생겨 회복 경로가 돈다.
        evaluate(watoo.grd.nextroute.domain.journey.entity.TransferStatus.COMFORTABLE,
                0, NotificationChannel.PUSH);

        assertThat(statusOfDeliveryKey("seeded-delivery"))
                .as("여유가 생긴 것과 '차가 곧 온다'는 서로 다른 이야기다")
                .isEqualTo("PENDING");
        assertThat(statusOfCheckpoint(JourneyCheckpoint.TRANSFER_RISK_AT_RISK))
                .as("위험 알림은 회복에서 밀려난다")
                .isEqualTo("CANCELED");
    }

    @Test
    @DisplayName("스위치를 내리면 접근 알림만 멈춘다")
    void killSwitchStopsApproachOnly() {
        properties.getApproach().setEnabled(false);

        evaluateWaiting(60, NotificationChannel.PUSH);
        evaluate(watoo.grd.nextroute.domain.journey.entity.TransferStatus.MISSED,
                0, NotificationChannel.PUSH);

        assertThat(checkpoints())
                .containsExactly(JourneyCheckpoint.TRANSFER_RISK_MISSED.name());
    }

    @Test
    @DisplayName("하차·정류장 접근은 어휘에 없다 — 서버가 보낼 수단 자체가 없다")
    void checkpointVocabularyStaysAtThree() {
        assertThat(JourneyCheckpoint.values())
                .as("N1·N3 은 모바일 local notification 소유다")
                .containsExactlyInAnyOrder(
                        JourneyCheckpoint.TRANSFER_RISK_AT_RISK,
                        JourneyCheckpoint.TRANSFER_RISK_MISSED,
                        JourneyCheckpoint.TRANSFER_TARGET_APPROACHING);
    }

    private List<String> checkpoints() {
        return jdbcTemplate.queryForList(
                "select checkpoint from journey_notification order by id", String.class);
    }

    private String statusOfDeliveryKey(String deliveryKey) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where delivery_key = ?",
                String.class, deliveryKey);
    }

    private String statusOfCheckpoint(JourneyCheckpoint checkpoint) {
        return jdbcTemplate.queryForObject(
                "select status from journey_notification where checkpoint = ?",
                String.class, checkpoint.name());
    }
}
