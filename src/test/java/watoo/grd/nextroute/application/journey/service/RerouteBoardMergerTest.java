package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.route.dto.PathInfo;
import watoo.grd.nextroute.application.route.dto.PathResult;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 보드 병합 규칙(#264 정리본 v1 §5, 논의 결과 8)을 경계값으로 고정한다.
 *
 * <p>값: 문턱 실시간 120 / 섞임 180 / 대기 +60, 교체 60, 재표시 +30, 여유 60초.
 */
@DisplayName("재탐색 보드 병합")
class RerouteBoardMergerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final String SLOT = RerouteBoardSlot.stopKey("SEOUL", "ST-B");
    private static final RerouteBoardMerger.Thresholds THRESHOLDS =
            new RerouteBoardMerger.Thresholds(120, 180, 60, 60, 30, Duration.ofSeconds(60));

    private final AtomicInteger ids = new AtomicInteger();
    private final RerouteBoardMerger merger =
            new RerouteBoardMerger(THRESHOLDS, () -> "new-" + ids.incrementAndGet());

    // ── 처음 뜸 ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("새 자리")
    class NewSlot {

        @ParameterizedTest(name = "실시간={0} 대기+{1} 이득 {2}초 → 보임={3}")
        @CsvSource({
                "true, 0, 119, false",
                "true, 0, 120, true",
                "false, 0, 179, false",
                "false, 0, 180, true",
                "true, 1, 179, false",
                "true, 1, 180, true",
                "true, 2, 240, true",
                "true, -1, 120, true"
        })
        @DisplayName("문턱 = (실시간 120 | 섞임 180) + 늘어난 대기 1회당 60")
        void threshold(boolean realtimeOnly, int extraWaits, int gain, boolean visible) {
            RerouteBoardMerger.Result result = merger.merge(List.of(),
                    input(evaluated(SLOT, gain, realtimeOnly, extraWaits, "fp-A", alightIn(600))),
                    NOW);

            assertThat(result.slots()).hasSize(visible ? 1 : 0);
        }

        @Test
        @DisplayName("문턱을 못 넘은 새 자리는 기억하지 않는다 — 다음에 처음 뜰 때도 원래 문턱이다")
        void belowThresholdIsNotRemembered() {
            RerouteBoardMerger.Result result = merger.merge(List.of(),
                    input(evaluated(SLOT, 100, true, 0, "fp-A", alightIn(600))), NOW);

            assertThat(result.slots()).isEmpty();
        }

        @Test
        @DisplayName("도착까지 60초 미만이면 새로 띄우지 않는다")
        void tooCloseIsNotAdded() {
            RerouteBoardMerger.Result result = merger.merge(List.of(),
                    input(evaluated(SLOT, 600, true, 0, "fp-A", alightIn(59))), NOW);

            assertThat(result.slots()).isEmpty();
        }

        @Test
        @DisplayName("보이게 되면 새 candidateId 를 받는다")
        void newSlotGetsFreshId() {
            RerouteBoardMerger.Result result = merger.merge(List.of(),
                    input(evaluated(SLOT, 300, true, 0, "fp-A", alightIn(600))), NOW);

            RerouteBoardSlot slot = result.slots().get(0);
            assertThat(slot.isVisible()).isTrue();
            assertThat(slot.candidate().candidateId()).isEqualTo("new-1");
            assertThat(slot.firstShownAt()).isEqualTo(NOW);
            assertThat(result.transitions()).containsEntry(RerouteBoardMerger.Transition.ADDED, 1);
        }
    }

    // ── 보이는 자리 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("보이는 자리")
    class VisibleSlot {

        @Test
        @DisplayName("같은 경로면 숫자와 경로를 새 값으로, candidateId 는 유지한다")
        void sameRouteKeepsId() {
            RerouteBoardSlot shown = visible("kept", "fp-A", 300);
            RerouteBoardMerger.Evaluated fresh = evaluated(SLOT, 420, true, 0, "fp-A", alightIn(500));

            RerouteBoardSlot slot = merger.merge(List.of(shown), input(fresh), NOW).slots().get(0);

            assertThat(slot.candidate().candidateId()).isEqualTo("kept");
            assertThat(slot.candidate().display().etaGainSec()).isEqualTo(420);
            assertThat(slot.lastGainSec()).isEqualTo(420);
            assertThat(slot.alightAt()).isEqualTo(alightIn(500));
            // 처음 보인 시각은 이어진다 — 같은 말풍선이다.
            assertThat(slot.firstShownAt()).isEqualTo(shown.firstShownAt());
        }

        @ParameterizedTest(name = "기존 300초, 다른 경로 {0}초 → 교체={1}")
        @CsvSource({"359, false", "360, true"})
        @DisplayName("다른 경로는 60초 이상 빨라야 교체한다. 아니면 기존을 숫자까지 그대로 둔다")
        void differentRouteNeedsMargin(int freshGain, boolean replaced) {
            RerouteBoardSlot shown = visible("kept", "fp-A", 300);

            RerouteBoardSlot slot = merger.merge(List.of(shown),
                    input(evaluated(SLOT, freshGain, true, 0, "fp-B", alightIn(500))), NOW).slots().get(0);

            if (replaced) {
                assertThat(slot.candidate().candidateId()).isEqualTo("new-1");
                assertThat(slot.routeFingerprint()).isEqualTo("fp-B");
            } else {
                assertThat(slot).isEqualTo(shown);
            }
        }

        @Test
        @DisplayName("이득이 문턱 아래로 떨어지면 바로 숨긴다")
        void dropsBelowThresholdHidesImmediately() {
            RerouteBoardSlot slot = merger.merge(List.of(visible("kept", "fp-A", 300)),
                    input(evaluated(SLOT, 119, true, 0, "fp-A", alightIn(500))), NOW).slots().get(0);

            assertThat(slot.isVisible()).isFalse();
            assertThat(slot.candidate()).isNull();
            assertThat(slot.hiddenAt()).isEqualTo(NOW);
            assertThat(slot.lastGainSec()).isEqualTo(119);
        }

        @Test
        @DisplayName("평가했는데 쓸 경로가 없으면 숨기고, 이득은 비운다")
        void noRouteHides() {
            RerouteBoardSlot slot = merger.merge(List.of(visible("kept", "fp-A", 300)),
                    input(new RerouteBoardMerger.Evaluated(SLOT, RerouteOriginKind.RIDE_INTERIOR, null,
                            0, true, 0, null, alightIn(500))), NOW).slots().get(0);

            assertThat(slot.isVisible()).isFalse();
            assertThat(slot.lastGainSec()).isNull();
        }
    }

    // ── 숨긴 자리 ───────────────────────────────────────────────────────────

    @Nested
    @DisplayName("숨긴 자리")
    class HiddenSlot {

        @ParameterizedTest(name = "실시간 문턱 120, 이득 {0}초 → 재표시={1}")
        @CsvSource({"149, false", "150, true"})
        @DisplayName("다시 뜨려면 문턱 + 30초를 넘어야 한다")
        void reshowNeedsMargin(int gain, boolean reshown) {
            RerouteBoardSlot slot = merger.merge(List.of(hidden()),
                    input(evaluated(SLOT, gain, true, 0, "fp-A", alightIn(500))), NOW).slots().get(0);

            assertThat(slot.isVisible()).isEqualTo(reshown);
            if (reshown) {
                assertThat(slot.candidate().candidateId()).isEqualTo("new-1");
                assertThat(slot.hiddenAt()).isNull();
            } else {
                assertThat(slot.hiddenAt()).isEqualTo(NOW.minusSeconds(60));
            }
        }
    }

    // ── 지움과 유지 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("지움과 유지")
    class RemoveAndKeep {

        @ParameterizedTest(name = "도착까지 {0}초 → 남음={1}")
        @CsvSource({"59, false", "60, true"})
        @DisplayName("도착까지 60초 미만인 자리는 지운다(평가 여부와 무관)")
        void leadTimeRemoves(int secondsAway, boolean kept) {
            RerouteBoardSlot shown = visibleAt("kept", "fp-A", 300, alightIn(secondsAway));

            RerouteBoardMerger.Result result = merger.merge(List.of(shown), input(), NOW);

            assertThat(result.slots()).hasSize(kept ? 1 : 0);
        }

        @Test
        @DisplayName("지난 자리는 숨김 기록까지 지운다 — 다시 나타나면 원래 문턱으로 처음 뜬다")
        void passedClearsHiddenMemory() {
            RerouteBoardMerger.Result result = merger.merge(List.of(hidden()),
                    new RerouteBoardMerger.Input(Map.of(), Set.of(SLOT)), NOW);

            assertThat(result.slots()).isEmpty();
            assertThat(result.transitions()).containsEntry(RerouteBoardMerger.Transition.REMOVED, 1);
        }

        @Test
        @DisplayName("이번에 평가하지 못한 자리는 그대로 둔다 — 일시 장애가 말풍선을 지우지 않게")
        void unevaluatedIsKept() {
            RerouteBoardSlot shown = visible("kept", "fp-A", 300);

            RerouteBoardMerger.Result result = merger.merge(List.of(shown), input(), NOW);

            assertThat(result.slots()).containsExactly(shown);
        }

        @Test
        @DisplayName("현재 위치 자리는 시간으로 사라지지 않는다")
        void hereHasNoLeadTime() {
            RerouteBoardSlot here = new RerouteBoardSlot(RerouteBoardSlot.HERE,
                    RerouteOriginKind.TRANSFER_WAIT, RerouteBoardSlot.State.VISIBLE,
                    candidate("kept", 300), "fp-A", 300, NOW.minusSeconds(120), null,
                    NOW.minusSeconds(60), null);

            assertThat(merger.merge(List.of(here), input(), NOW).slots()).containsExactly(here);
        }
    }

    // ── 준비 ────────────────────────────────────────────────────────────────

    private RerouteBoardMerger.Input input(RerouteBoardMerger.Evaluated... evaluated) {
        return new RerouteBoardMerger.Input(
                java.util.Arrays.stream(evaluated).collect(java.util.stream.Collectors.toMap(
                        RerouteBoardMerger.Evaluated::slotKey, e -> e)),
                Set.of());
    }

    private RerouteBoardMerger.Evaluated evaluated(String slot, int gain, boolean realtimeOnly,
                                                   int extraWaits, String fingerprint,
                                                   Instant alightAt) {
        return new RerouteBoardMerger.Evaluated(slot, RerouteOriginKind.RIDE_INTERIOR,
                candidate(null, gain), gain, realtimeOnly, extraWaits, fingerprint, alightAt);
    }

    private RerouteBoardSlot visible(String id, String fingerprint, int gain) {
        return visibleAt(id, fingerprint, gain, alightIn(600));
    }

    private RerouteBoardSlot visibleAt(String id, String fingerprint, int gain, Instant alightAt) {
        return new RerouteBoardSlot(SLOT, RerouteOriginKind.RIDE_INTERIOR,
                RerouteBoardSlot.State.VISIBLE, candidate(id, gain), fingerprint, gain,
                NOW.minusSeconds(180), null, NOW.minusSeconds(60), alightAt);
    }

    private RerouteBoardSlot hidden() {
        return new RerouteBoardSlot(SLOT, RerouteOriginKind.RIDE_INTERIOR,
                RerouteBoardSlot.State.HIDDEN, null, null, 100, null, NOW.minusSeconds(60),
                NOW.minusSeconds(60), alightIn(600));
    }

    private static Instant alightIn(int seconds) {
        return NOW.plusSeconds(seconds);
    }

    private static RerouteCandidate candidate(String id, int gain) {
        return new RerouteCandidate(id, RerouteProposalKind.REMAINING_PATH,
                new RerouteCandidateApply.RemainingPathApply(1,
                        new PathResult(1, new PathInfo(20, 1500, 300, 1, "A", "B", null),
                                List.of(), List.of())),
                new RerouteCandidateDisplay(NOW.plusSeconds(1800), 1800, gain, 1, 120,
                        "REALTIME", "GOOD", NOW, List.of("360 승차")));
    }
}
