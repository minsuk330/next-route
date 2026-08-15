package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPushReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 언제 잠금화면을 원격으로 깨울지.
 *
 * <p>두 방향의 실패가 있다.
 * <ul>
 *   <li><b>너무 자주 보낸다</b> — 링은 5초마다 도는데 매번 보내면 배터리와 APNs 예산을 먹는다</li>
 *   <li><b>중요한 것을 놓친다</b> — 다음 역이 바뀌었는데 안 보내면 사용자가 역을 지나친다</li>
 * </ul>
 */
@DisplayName("진행 링 push 발송 판정")
class SubwayRingPushDeciderTest {

    private static final Instant T0 = Instant.parse("2026-08-15T09:00:00Z");
    private static final Long JOURNEY_ID = 501L;

    private final JourneySubwayRingProperties properties = new JourneySubwayRingProperties();
    private final SubwayRingPushDecider decider = new SubwayRingPushDecider(properties);

    @Nested
    @DisplayName("중요 이벤트는 반드시 보낸다")
    class CriticalEvents {

        @Test
        @DisplayName("첫 화면 — 놓치면 위젯이 '확인 중'에서 영영 안 벗어난다")
        void firstScreenAlwaysGoes() {
            Optional<SubwayRingPushDecider.Decision> decision = decider.decide(null, ring());

            assertThat(decision).isPresent();
            assertThat(decision.get().reason()).isEqualTo(SubwayRingPushReason.NEXT_STATION);
            assertThat(decision.get().critical()).isTrue();
        }

        @Test
        @DisplayName("다음 역이 바뀌면 보낸다")
        void nextStationChange() {
            SubwayRingState moved = ringBuilder()
                    .sequence(2).key("1002000224").name("선릉").build();

            SubwayRingPushDecider.Decision decision = decider.decide(ring(), moved).orElseThrow();

            assertThat(decision.reason()).isEqualTo(SubwayRingPushReason.NEXT_STATION);
            assertThat(decision.critical()).isTrue();
        }

        @Test
        @DisplayName("하차 국면으로 넘어가면 보낸다")
        void phaseChange() {
            SubwayRingState alighting = ringBuilder().phase(SubwayRingPhase.SUBWAY_ALIGHT).build();

            assertThat(decider.decide(ring(), alighting).orElseThrow().reason())
                    .isEqualTo(SubwayRingPushReason.PHASE);
        }

        @Test
        @DisplayName("신선도가 뒤집히면 보낸다 — 양쪽 방향 다")
        void qualityFlipsBothWays() {
            SubwayRingState stale = ringBuilder().quality(SubwayRingQuality.STALE).build();

            assertThat(decider.decide(ring(), stale).orElseThrow().reason())
                    .isEqualTo(SubwayRingPushReason.QUALITY);
            assertThat(decider.decide(stale, ring()).orElseThrow().reason())
                    .isEqualTo(SubwayRingPushReason.QUALITY);
        }

        @Test
        @DisplayName("다음 역에 실제로 도착하면 보낸다")
        void arrival() {
            SubwayRingState arrived = ringBuilder().progress("1.0000").build();

            SubwayRingPushDecider.Decision decision =
                    decider.decide(ring(), arrived).orElseThrow();

            assertThat(decision.reason()).isEqualTo(SubwayRingPushReason.ARRIVED);
            assertThat(decision.critical()).isTrue();
        }

        @Test
        @DisplayName("종료는 링과 무관하게 언제나 critical 이다")
        void terminalIsAlwaysCritical() {
            SubwayRingPushDecider.Decision terminal = decider.terminal(T0);

            assertThat(terminal.reason()).isEqualTo(SubwayRingPushReason.END);
            assertThat(terminal.critical()).isTrue();
        }
    }

    @Nested
    @DisplayName("같은 화면이면 보내지 않는다")
    class Quiet {

        @Test
        @DisplayName("아무것도 안 바뀌면 보낼 이유가 없다")
        void identicalRingIsSilent() {
            assertThat(decider.decide(ring(), ring())).isEmpty();
        }

        @Test
        @DisplayName("updatedAt 만 바뀌는 것으로는 보내지 않는다 — 그러면 매 tick 나간다")
        void updatedAtAloneIsSilent() {
            SubwayRingState reticked = ringBuilder().updatedAt(T0.plusSeconds(5)).build();

            assertThat(decider.decide(ring(), reticked)).isEmpty();
        }

        @Test
        @DisplayName("도착 예정이 몇 초 흔들리는 것으로는 보내지 않는다")
        void tinyEtaDriftIsSilent() {
            SubwayRingState drifted = ringBuilder()
                    .estimatedAt(T0.plusSeconds(92))
                    .updatedAt(T0.plusSeconds(2))
                    .build();

            assertThat(decider.decide(ring(), drifted)).isEmpty();
        }

        @Test
        @DisplayName("도착 예정이 크게 밀리면 앵커 갱신으로 보낸다 — 다만 critical 은 아니다")
        void bigEtaShiftSendsAnchor() {
            SubwayRingState delayed = ringBuilder().estimatedAt(T0.plusSeconds(300)).build();

            SubwayRingPushDecider.Decision decision =
                    decider.decide(ring(), delayed).orElseThrow();

            assertThat(decision.reason()).isEqualTo(SubwayRingPushReason.ANCHOR);
            assertThat(decision.critical()).isFalse();
        }

        @Test
        @DisplayName("진행률이 눈금 하나를 넘으면 보낸다")
        void progressBucketCrossingSends() {
            SubwayRingState advanced = ringBuilder().progress("0.7500").build();

            assertThat(decider.decide(ring(), advanced)).isPresent();
        }
    }

    @Test
    @DisplayName("링이 없으면 보낼 것도 없다")
    void noRingNoPush() {
        assertThat(decider.decide(ring(), null)).isEmpty();
    }

    // ── 픽스처 ──────────────────────────────────────────────────────────────

    private SubwayRingState ring() {
        return ringBuilder().build();
    }

    private Fixture ringBuilder() {
        return new Fixture();
    }

    /** 한 필드만 바꿔 가며 비교하려고 둔다. 레코드를 매번 통째로 적으면 어디가 다른지 안 보인다. */
    private static final class Fixture {
        private int sequence = 1;
        private String key = "1002000223";
        private String name = "역삼";
        private String progress = "0.4500";
        private Instant estimatedAt = T0.plusSeconds(90);
        private Instant updatedAt = T0;
        private SubwayRingPhase phase = SubwayRingPhase.RIDE_SUBWAY;
        private SubwayRingQuality quality = SubwayRingQuality.NORMAL;

        Fixture sequence(int value) {
            this.sequence = value;
            return this;
        }

        Fixture key(String value) {
            this.key = value;
            return this;
        }

        Fixture name(String value) {
            this.name = value;
            return this;
        }

        Fixture progress(String value) {
            this.progress = value;
            return this;
        }

        Fixture estimatedAt(Instant value) {
            this.estimatedAt = value;
            return this;
        }

        Fixture updatedAt(Instant value) {
            this.updatedAt = value;
            return this;
        }

        Fixture phase(SubwayRingPhase value) {
            this.phase = value;
            return this;
        }

        Fixture quality(SubwayRingQuality value) {
            this.quality = value;
            return this;
        }

        SubwayRingState build() {
            return new SubwayRingState(JOURNEY_ID, 1, 4, 0,
                    sequence, key, name, "삼성", 2,
                    new BigDecimal(progress), T0, new BigDecimal("0.90"),
                    estimatedAt, T0.plusSeconds(120),
                    phase, quality, T0, updatedAt);
        }
    }
}
