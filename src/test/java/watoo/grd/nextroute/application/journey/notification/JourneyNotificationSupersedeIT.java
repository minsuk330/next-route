package watoo.grd.nextroute.application.journey.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 낡은 알림은 새 알림이 밀어낸다.
 *
 * <p>두 가지를 막는다. 하나는 <b>순서 뒤집힘</b> — {@code AT_RISK} 와 {@code MISSED} 는 별개
 * 행이라 앞 것이 재시도로 밀리면 사용자가 "놓쳤다" 다음에 "놓칠 것 같다"를 본다. 다른 하나는
 * <b>회복 뒤 지연 발송</b> — 상태가 좋아져도 쌓여 있던 알림이 최대 30분 동안 발송 대상으로 남는다.
 */
@DisplayName("낡은 알림은 새 알림이 밀어낸다")
class JourneyNotificationSupersedeIT extends AbstractJourneyNotificationIT {

    @Test
    @DisplayName("놓침이 생기면 대기 중이던 위험 알림은 취소된다")
    void missedSupersedesPendingAtRisk() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH);

        List<Map<String, Object>> rows = notifications();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("status")).isEqualTo("CANCELED");
        assertThat(rows.get(0).get("last_failure_reason")).isEqualTo("superseded");
        assertThat(rows.get(1).get("status"))
                .as("방금 만든 행까지 밀어내면 알림이 하나도 안 나간다")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("채널이 여럿이어도 이번에 만든 행은 모두 살아남는다")
    void newRowsOfAllChannelsSurvive() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH, NotificationChannel.TOSS);
        evaluate(TransferStatus.MISSED, NotificationChannel.PUSH, NotificationChannel.TOSS);

        assertThat(notifications())
                .filteredOn(row -> "PENDING".equals(row.get("status")))
                .as("마지막 발송 키만 남기면 두 번째 채널이 조용히 취소된다")
                .hasSize(2);
    }

    @Test
    @DisplayName("상태가 회복되면 쌓인 알림을 밀어내고 마커를 비운다")
    void recoveryCancelsPendingAndClearsMarker() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement()
                .satisfies(row -> assertThat(row.get("status")).isEqualTo("CANCELED"));
        assertThat(marker())
                .as("비우지 않으면 다시 나빠져도 '이미 알렸다'에 걸려 안 나간다")
                .isNull();
    }

    @Test
    @DisplayName("회복 뒤 다시 나빠지면 새 알림이 나간다")
    void worseningAfterRecoveryNotifiesAgain() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);

        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        assertThat(notifications())
                .filteredOn(row -> "PENDING".equals(row.get("status")))
                .as("같은 발송 키는 ON CONFLICT 에 막히므로 재알림은 새 행이 아니라 기존 행이다")
                .isEmpty();
        assertThat(marker()).isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("모름에서는 마커를 비우지 않는다")
    void unknownDoesNotClearMarker() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);

        evaluate(TransferStatus.UNKNOWN, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement()
                .satisfies(row -> assertThat(row.get("status"))
                        .as("잠깐 근거를 못 구한 것으로 알림을 취소하면 위험을 놓친다")
                        .isEqualTo("PENDING"));
        assertThat(marker()).isEqualTo("AT_RISK");
    }

    @Test
    @DisplayName("발송 중인 행은 밀어내지 않는다")
    void processingRowIsUntouched() {
        evaluate(TransferStatus.AT_RISK, NotificationChannel.PUSH);
        jdbcTemplate.update("update journey_notification set status = 'PROCESSING'");

        evaluate(TransferStatus.CATCHABLE, NotificationChannel.PUSH);

        assertThat(notifications()).singleElement()
                .satisfies(row -> assertThat(row.get("status"))
                        .as("발송 중인 행은 relay 의 발송 직전 재확인이 맡는다")
                        .isEqualTo("PROCESSING"));
    }
}
