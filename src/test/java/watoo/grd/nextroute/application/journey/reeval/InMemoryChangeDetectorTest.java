package watoo.grd.nextroute.application.journey.reeval;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("변화 감지 기준선")
class InMemoryChangeDetectorTest {

    private final InMemoryChangeDetector<String, String> detector = new InMemoryChangeDetector<>();

    @Test
    @DisplayName("처음 본 키는 바뀐 것으로 치지 않는다 — 비교할 과거가 없다")
    void firstSightIsBaselineOnly() {
        assertThat(detector.changed(Map.of("a", "1"))).isEmpty();
    }

    @Test
    @DisplayName("지문이 달라진 키와 사라진 키를 바뀐 것으로 고른다")
    void changedAndVanishedKeys() {
        detector.accept(Map.of("a", "1", "b", "1", "c", "1"), Set.of());

        assertThat(detector.changed(Map.of("a", "2", "b", "1"))).containsExactlyInAnyOrder("a", "c");
    }

    @Test
    @DisplayName("반영 못 해 남긴 키는 다음 관측에서 다시 바뀐 것으로 나온다 — 이벤트를 잃지 않는다")
    void keptKeyReEmits() {
        detector.accept(Map.of("a", "1"), Set.of());
        Map<String, String> next = Map.of("a", "2");
        assertThat(detector.changed(next)).containsExactly("a");

        detector.accept(next, Set.of("a"));

        assertThat(detector.changed(next)).containsExactly("a");
        detector.accept(next, Set.of());
        assertThat(detector.changed(next)).isEmpty();
    }

    @Test
    @DisplayName("비우면 다음 관측은 기준선만 쌓는다")
    void resetForgetsBaseline() {
        detector.accept(Map.of("a", "1"), Set.of());
        detector.reset();

        assertThat(detector.changed(Map.of("a", "2"))).isEmpty();
    }

    @Test
    @DisplayName("사라졌던 키가 다시 나타나면 바뀐 것이다 — 다음 열차가 보이면 모름이 예정 시각이 된다")
    void reappearingKeyIsAChange() {
        detector.accept(Map.of("a", "1"), Set.of());
        Map<String, String> empty = Map.of();
        assertThat(detector.changed(empty)).containsExactly("a");
        detector.accept(empty, Set.of());

        assertThat(detector.changed(Map.of("a", "2"))).containsExactly("a");
    }
}
