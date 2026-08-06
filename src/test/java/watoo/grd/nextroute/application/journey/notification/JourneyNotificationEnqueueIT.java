package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 언제 알림이 쌓이고 언제 안 쌓이는지.
 *
 * <p>가장 중요한 것은 두 가지다. <b>같은 상태로는 다시 쌓이지 않고</b>(10초마다 도는 평가가
 * 사용자에게 같은 이야기를 반복하지 않게), <b>나빠지면 별개 행으로 쌓인다</b>(승격이
 * {@code ON CONFLICT} 에 삼켜지지 않게).
 */
@DisplayName("평가가 나빠진 환승만 outbox 에 쌓는다")
class JourneyNotificationEnqueueIT extends AbstractJourneyNotificationIT {

    @Test
    @DisplayName("여유 있는 상태는 아무것도 쌓지 않는다")
    void comfortableEnqueuesNothing() {
        evaluate(TransferStatus.COMFORTABLE, NotificationChannel.PUSH);
        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);

        assertThat(notifications()).isEmpty();
        assertThat(marker()).isNull();
    }

    @Test
    @DisplayName("위험해지면 한 건 쌓고 마커를 남긴다")
    void atRiskEnqueuesOnceAndMarks() {
        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement().satisfies(row -> {
            assertThat(row.get("checkpoint")).isEqualTo("TRANSFER_RISK_AT_RISK");
            assertThat(row.get("channel")).isEqualTo("PUSH");
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("user_id")).isEqualTo(userId);
            assertThat(row.get("scope_type")).isEqualTo("TRANSFER");
            assertThat(row.get("scope_id")).isEqualTo(0);
        });
        assertThat(marker()).isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("같은 상태로 여섯 번 더 평가해도 행은 그대로 하나다")
    void repeatedSameStatusDoesNotAccumulate() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        for (int tick = 0; tick < 6; tick++) {
            evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        }

        assertThat(notifications())
                .as("마커가 있으면 INSERT 시도 자체를 하지 않는다")
                .hasSize(1);
    }

    @Test
    @DisplayName("놓침으로 승격되면 별개 행이 생긴다 — 발송 키가 다르다")
    void promotionToMissedCreatesSecondRow() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("checkpoint")).isEqualTo("TRANSFER_RISK_AT_RISK");
        assertThat(rows.get(1).get("checkpoint")).isEqualTo("TRANSFER_RISK_MISSED");
        assertThat(rows.get(0).get("delivery_key"))
                .as("체크포인트가 상태를 품지 않으면 승격이 ON CONFLICT 에 삼켜진다")
                .isNotEqualTo(rows.get(1).get("delivery_key"));
        assertThat(marker()).isEqualTo("MISSED");
    }

    @Test
    @DisplayName("모름으로 바뀌어도 마커는 그대로 둔다")
    void unknownKeepsMarker() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.UNKNOWN, NotificationChannel.PUSH);

        assertThat(notifications())
                .as("모름은 알릴 상태가 아니다. 순위표에서 CATCHABLE 보다 나쁘다는 이유로 알리면 안 된다")
                .hasSize(1);
        assertThat(marker())
                .as("잠깐 모르는 것과 실제로 좋아진 것은 다르다")
                .isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("채널이 둘이면 행도 둘, 논리 키는 하나다")
    void twoChannelsTwoRows() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH, NotificationChannel.TOSS);

        assertThat(notifications()).hasSize(2);
        assertThat(jdbcTemplate.queryForList(
                "select distinct logical_checkpoint_key from journey_notification", String.class))
                .hasSize(1);
    }

    @Test
    @DisplayName("보낼 채널이 없으면 아무것도 안 하고 마커도 안 쓴다")
    void emptyChannelsWritesNothing() {
        evaluate(TransferStatus.AT_RISK);

        assertThat(notifications()).isEmpty();
        assertThat(marker())
                .as("마커를 앞당겨 쓰면 그 환승은 채널이 생겨도 영영 알림이 없다")
                .isNull();
    }

    @Test
    @DisplayName("이미 지나온 환승은 알리지 않는다")
    void pastTransferIsSkipped() {
        // 갈아탈 대기 단계는 2번인데 지금 3번을 진행 중이다.
        evaluate(TransferStatus.AT_RISK, 3, NotificationChannel.PUSH);

        assertThat(notifications()).isEmpty();
        assertThat(marker()).isNull();
    }

    @Test
    @DisplayName("kill switch 를 내리면 평가는 돌되 알림만 안 쌓인다")
    void killSwitchStopsEnqueueOnly() {
        properties.setEnqueueEnabled(false);

        boolean stored = evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(stored).as("평가 결과 저장은 계속돼야 한다").isTrue();
        assertThat(notifications()).isEmpty();
        assertThat(marker()).isNull();
    }

    @Test
    @DisplayName("행에 적힌 두 version 은 소유권 확인이 올린 뒤의 값이다")
    void versionsAreTakenAfterCas() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        long evaluationVersion = jdbcTemplate.queryForObject(
                "select evaluation_version from journey_session where id = ?",
                Long.class, journey.getId());
        assertThat(notifications()).singleElement().satisfies(row -> {
            assertThat(row.get("evaluation_version"))
                    .as("PR5 가 이 값으로 낡은 알림을 거른다. 올리기 전 값이면 늘 낡은 것으로 보인다")
                    .isEqualTo(evaluationVersion);
            assertThat(row.get("command_version")).isEqualTo(1L);
        });
        assertThat(evaluationVersion).isEqualTo(1L);
    }

    @Test
    @DisplayName("놓침 알림의 만료는 바닥(180초)이 지켜 준다 — 도착 시각이 이미 과거여도 살아 있다")
    void missedNotificationGetsMinimumTtl() {
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement().satisfies(row -> {
            java.time.Instant expiresAt = ((java.sql.Timestamp) row.get("expires_at")).toInstant();
            assertThat(expiresAt)
                    .as("도착 시각(과거)을 그대로 만료로 쓰면 relay 가 첫 claim 에서 취소한다")
                    .isEqualTo(java.time.Instant.now(clock).plusSeconds(180));
        });
    }

    @Test
    @DisplayName("쌓인 뒤 커밋되면 relay 가 즉시 깨어난다")
    void enqueueWakesRelayAfterCommit() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH, NotificationChannel.TOSS);

        assertThat(relayWaker.await(0))
                .as("커밋 전에 깨우면 relay 가 아직 없는 행을 찾아 헛돈다")
                .isTrue();
        assertThat(relayWaker.await(0))
                .as("평가 한 번에 신호도 한 번이다 — 행마다 깨우면 이벤트 시간만 태운다")
                .isFalse();
    }

    @Test
    @DisplayName("아무것도 안 쌓이면 깨우지 않는다")
    void nothingEnqueuedDoesNotWake() {
        evaluate(TransferStatus.COMFORTABLE, NotificationChannel.PUSH);

        assertThat(relayWaker.await(0)).isFalse();
    }

    @Test
    @DisplayName("경로 개정본이 다르면 CTE 가 걸러 아무것도 안 쌓인다")
    void routeRevisionMismatchEnqueuesNothing() {
        jdbcTemplate.update("update journey_session set route_revision = 2 where id = ?",
                journey.getId());

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(notifications())
                .as("재탐색 뒤 이전 개정본의 알림은 나가면 안 된다")
                .isEmpty();
    }
}
