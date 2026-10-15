package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 보드 push 가 대신한 위험 알림을 문구 재료로 적는 규칙(#428 PR-2). */
@DisplayName("보드 push — 대신한 위험 표시")
class RerouteBoardReplacedRiskTest {

    private static final String AT_RISK = "7:1:TRANSFER:0:TRANSFER_RISK_AT_RISK";
    private static final String MISSED = "7:1:TRANSFER:1:TRANSFER_RISK_MISSED";

    @Test
    @DisplayName("여럿을 대신했으면 가장 심한 것 — 놓침이 빠듯을 이긴다(순서 무관)")
    void mostSevereWins() {
        Map<String, Object> payload = Map.of("gainSec", 300);

        assertThat(RerouteBoardPushTxService.withReplacedRisk(payload, List.of(AT_RISK, MISSED)))
                .containsEntry("replacedRisk", "MISSED");
        assertThat(RerouteBoardPushTxService.withReplacedRisk(payload, List.of(MISSED, AT_RISK)))
                .containsEntry("replacedRisk", "MISSED");
        assertThat(RerouteBoardPushTxService.withReplacedRisk(payload, List.of(AT_RISK)))
                .containsEntry("replacedRisk", "AT_RISK")
                .containsEntry("gainSec", 300);
    }

    @Test
    @DisplayName("대신한 게 없거나 위험 키가 아니면 원본 맵 그대로이고, 원본은 고치지 않는다")
    void noReplacementKeepsOriginal() {
        Map<String, Object> payload = new HashMap<>(Map.of("gainSec", 300));

        assertThat(RerouteBoardPushTxService.withReplacedRisk(payload, List.of())).isSameAs(payload);
        assertThat(RerouteBoardPushTxService.withReplacedRisk(payload,
                List.of("7:1:TRANSFER:0:TRANSFER_TARGET_APPROACHING", "garbage"))).isSameAs(payload);
        RerouteBoardPushTxService.withReplacedRisk(payload, List.of(MISSED));
        assertThat(payload).doesNotContainKey("replacedRisk");
    }
}
