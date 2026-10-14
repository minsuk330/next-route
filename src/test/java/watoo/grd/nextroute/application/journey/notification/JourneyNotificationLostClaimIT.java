package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyDueClaim;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소유권을 잃은 워커는 알림도 마커도 건드리지 않는다.
 *
 * <p>이 성질은 <b>순서</b>가 만든다. 알림을 소유권 확인보다 앞에 두면, 진 워커가 "알렸다" 마커를
 * 앞당겨 찍어 <b>이긴 워커의 알림을 영구히 막는다</b>. 겉으로는 아무 오류도 나지 않고 알림만
 * 조용히 사라지므로 테스트로 고정한다.
 */
@DisplayName("lease 를 잃은 워커는 알림을 남기지 않는다")
class JourneyNotificationLostClaimIT extends AbstractJourneyNotificationIT {

    @Test
    @DisplayName("낡은 선점 증서로 저장하면 알림도 마커도 남지 않는다")
    void lostClaimLeavesNothing() {
        // 다른 워커가 이미 가져갔다 — 여정의 다음 평가 시각이 내가 심어 둔 값이 아니다.
        Instant otherWorkerLease = LEASE_UNTIL.plusSeconds(60);
        jdbcTemplate.update("update journey_session set next_evaluation_at = ? where id = ?",
                java.sql.Timestamp.from(otherWorkerLease), journey.getId());

        JourneyEvaluationOutcome outcome = new JourneyEvaluationOutcome(
                journey.getId(), 1, batchOf(TransferStatus.AT_RISK), true);
        boolean stored = scheduleWriter.finish(
                new JourneyDueClaim(journey.getId(), LEASE_UNTIL, LEASE_UNTIL),
                outcome, LEASE_UNTIL.plusSeconds(30), false,
                new NotificationContext(userId, 0, List.of(NotificationChannel.PUSH)));

        assertThat(stored).isFalse();
        assertThat(notifications()).isEmpty();
        assertThat(marker())
                .as("진 워커가 마커를 앞당겨 찍으면 이긴 워커의 알림이 영영 안 나간다")
                .isNull();
    }

    @Test
    @DisplayName("소유권을 되찾으면 알림이 정상적으로 나간다")
    void winningWorkerStillNotifies() {
        Instant otherWorkerLease = LEASE_UNTIL.plusSeconds(60);
        jdbcTemplate.update("update journey_session set next_evaluation_at = ? where id = ?",
                java.sql.Timestamp.from(otherWorkerLease), journey.getId());
        scheduleWriter.finish(
                new JourneyDueClaim(journey.getId(), LEASE_UNTIL, LEASE_UNTIL),
                new JourneyEvaluationOutcome(journey.getId(), 1, batchOf(TransferStatus.AT_RISK), true),
                LEASE_UNTIL.plusSeconds(30), false,
                new NotificationContext(userId, 0, List.of(NotificationChannel.PUSH)));

        boolean stored = scheduleWriter.finish(
                new JourneyDueClaim(journey.getId(), otherWorkerLease, otherWorkerLease),
                new JourneyEvaluationOutcome(journey.getId(), 1, batchOf(TransferStatus.AT_RISK), true),
                otherWorkerLease.plusSeconds(30), false,
                new NotificationContext(userId, 0, List.of(NotificationChannel.PUSH)));

        assertThat(stored).isTrue();
        assertThat(notifications()).hasSize(1);
        assertThat(marker()).isEqualTo("AT_RISK");
    }
}
