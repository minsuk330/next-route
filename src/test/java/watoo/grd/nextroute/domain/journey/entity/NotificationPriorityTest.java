package watoo.grd.nextroute.domain.journey.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 발송 우선순위 매핑 표(#451 PR-2). 1차 기준은 체크포인트 — 놓침·빠듯 0, 접근 1, 그 밖(재탐색·보드·관리자 시험) 2.
 */
@DisplayName("알림 우선순위 매핑 표")
class NotificationPriorityTest {

    @Test
    @DisplayName("체크포인트마다 값이 하나씩 정해져 있다(새 체크포인트를 더하면 이 표도 고쳐야 한다)")
    void checkpointTable() {
        Map<JourneyCheckpoint, Integer> expected = new EnumMap<>(JourneyCheckpoint.class);
        expected.put(JourneyCheckpoint.TRANSFER_RISK_MISSED, 0);
        expected.put(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, 0);
        expected.put(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING, 1);
        expected.put(JourneyCheckpoint.REROUTE_SUGGESTED, 2);

        assertThat(expected.keySet()).containsExactlyInAnyOrder(JourneyCheckpoint.values());
        expected.forEach((checkpoint, value) ->
                assertThat((int) NotificationPriority.of(checkpoint).value()).as(checkpoint.name()).isEqualTo(value));
    }

    @Test
    @DisplayName("관리자 시험 알림은 체크포인트와 무관하게 2 — 운영 알림을 앞지르지 않는다")
    void adminTestIsRelaxed() {
        assertThat((int) NotificationPriority.ADMIN_TEST.value()).isEqualTo(2);
    }

    @Test
    @DisplayName("여유(2)는 V70 칼럼 기본값과 같다 — 칼럼을 안 주는 삽입도 여유로 떨어진다")
    void relaxedMatchesColumnDefault() {
        assertThat((int) NotificationPriority.RELAXED.value()).isEqualTo(2);
    }
}
