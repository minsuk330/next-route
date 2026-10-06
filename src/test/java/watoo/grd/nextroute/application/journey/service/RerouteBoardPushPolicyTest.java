package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * push 조건(#264 정리본 v1 §6)을 경계값으로 고정한다. 300초 이득, 하차 120초 여유, 시작·수락 3분,
 * 새 기회 +120초.
 */
@DisplayName("재탐색 보드 push 정책")
class RerouteBoardPushPolicyTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final Instant STARTED = NOW.minusSeconds(600);
    private final JourneyRerouteProperties properties = new JourneyRerouteProperties();

    @ParameterizedTest(name = "이득 {0}초 → push={1}")
    @CsvSource({"299, false", "300, true"})
    @DisplayName("300초 이상 빨라야 깨운다")
    void minimumGain(int gain, boolean push) {
        assertThat(decide(List.of(slot("S1", "fp", gain, NOW.plusSeconds(600))), null, false, STARTED, null))
                .isEqualTo(push);
    }

    @ParameterizedTest(name = "하차까지 {0}초 → push={1}")
    @CsvSource({"119, false", "120, true"})
    @DisplayName("하차까지 120초 미만이면 보내지 않는다 — 읽고 문 앞으로 갈 시간이 없다")
    void leadTime(int seconds, boolean push) {
        assertThat(decide(List.of(slot("S1", "fp", 400, NOW.plusSeconds(seconds))), null, false, STARTED, null))
                .isEqualTo(push);
    }

    @Test
    @DisplayName("현재 위치·대기 자리는 하차 여유를 보지 않는다")
    void positionSlotHasNoLead() {
        assertThat(decide(List.of(slot("HERE", "fp", 400, null)), null, false, STARTED, null)).isTrue();
    }

    @Test
    @DisplayName("시작·수락 뒤 3분 동안은 새 기회 push 를 막는다 — 위험+대안은 보낸다")
    void quietAfterStartOrAccept() {
        List<RerouteBoardSlot> slots = List.of(slot("S1", "fp", 400, NOW.plusSeconds(600)));

        assertThat(decide(slots, null, false, NOW.minusSeconds(179), null)).isFalse();
        assertThat(decide(slots, null, false, STARTED, NOW.minusSeconds(60))).isFalse();
        assertThat(decide(slots, null, false, NOW.minusSeconds(181), null)).isTrue();
        assertThat(decide(slots, null, true, NOW.minusSeconds(10), null)).as("위험 + 대안").isTrue();
    }

    @ParameterizedTest(name = "직전 같은 자리·경로 이득 300, 지금 {0}초 → push={1}")
    @CsvSource({"419, false", "420, true"})
    @DisplayName("같은 후보는 직전 push 보다 120초 이상 커져야 다시 보낸다")
    void noveltyByGain(int gain, boolean push) {
        assertThat(decide(List.of(slot("S1", "fp", gain, NOW.plusSeconds(600))),
                new RerouteBoardPushPolicy.LastPush("S1", "fp", 300), false, STARTED, null)).isEqualTo(push);
    }

    @Test
    @DisplayName("자리나 경로가 다르면 새 기회다")
    void noveltyBySlotOrRoute() {
        RerouteBoardPushPolicy.LastPush last = new RerouteBoardPushPolicy.LastPush("S1", "fp", 300);

        assertThat(decide(List.of(slot("S2", "fp", 310, NOW.plusSeconds(600))), last, false, STARTED, null)).isTrue();
        assertThat(decide(List.of(slot("S1", "fp-2", 310, NOW.plusSeconds(600))), last, false, STARTED, null)).isTrue();
    }

    @Test
    @DisplayName("여러 자리 중 가장 큰 이득을 고른다")
    void picksBestGain() {
        Optional<RerouteBoardPushPolicy.Decision> decision = RerouteBoardPushPolicy.decide(List.of(
                        slot("S1", "fp", 320, NOW.plusSeconds(600)), slot("S2", "fp", 500, NOW.plusSeconds(600))),
                null, false, STARTED, null, NOW, properties);

        assertThat(decision).get().extracting(d -> d.slot().slotKey()).isEqualTo("S2");
    }

    private boolean decide(List<RerouteBoardSlot> slots, RerouteBoardPushPolicy.LastPush last, boolean risk,
                           Instant startedAt, Instant acceptedAt) {
        return RerouteBoardPushPolicy.decide(slots, last, risk, startedAt, acceptedAt, NOW, properties).isPresent();
    }

    private static RerouteBoardSlot slot(String key, String fingerprint, int gain, Instant alightAt) {
        RerouteCandidate candidate = new RerouteCandidate("c-" + key, RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(1, new PathResult(1,
                        new PathInfo(20, 1500, 300, 1, "A", "B", null), List.of(), List.of())),
                new RerouteCandidateDisplay(NOW.plusSeconds(1800), 1800, gain, 1, 120, "REALTIME", "GOOD",
                        NOW, List.of("360 승차")));
        return new RerouteBoardSlot(key, alightAt == null ? RerouteOriginKind.CURRENT_POSITION
                : RerouteOriginKind.RIDE_INTERIOR, RerouteBoardSlot.State.VISIBLE, candidate, fingerprint, gain,
                NOW.minusSeconds(60), null, NOW, alightAt);
    }
}
