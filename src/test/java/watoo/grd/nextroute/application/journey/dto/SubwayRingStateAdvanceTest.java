package watoo.grd.nextroute.application.journey.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("진행 링 — 역 하나를 지났는가(#264 재탐색 보드 신호)")
class SubwayRingStateAdvanceTest {

    @Test
    @DisplayName("다음 역 순번이 앞으로 가면 지났다")
    void advancedWhenNextStationMovesForward() {
        assertThat(state(1, 1, 1, 5).advancedFrom(state(1, 1, 1, 4))).isTrue();
    }

    @Test
    @DisplayName("같거나 뒤로 가면 지나지 않았다")
    void notAdvancedOtherwise() {
        assertThat(state(1, 1, 1, 4).advancedFrom(state(1, 1, 1, 4))).isFalse();
        assertThat(state(1, 1, 1, 3).advancedFrom(state(1, 1, 1, 4))).isFalse();
    }

    @Test
    @DisplayName("직전이 없거나 다른 링(고정 판·단계)이면 견주지 않는다")
    void differentRingIsNotCompared() {
        assertThat(state(1, 1, 1, 5).advancedFrom(null)).isFalse();
        assertThat(state(1, 1, 2, 5).advancedFrom(state(1, 1, 1, 4))).isFalse();
        assertThat(state(2, 1, 1, 5).advancedFrom(state(1, 1, 1, 4))).isFalse();
    }

    private static SubwayRingState state(int revision, int legIndex, int bindingVersion, int next) {
        Instant now = Instant.parse("2026-10-07T09:00:00Z");
        return new SubwayRingState(7L, revision, legIndex, bindingVersion, next, "k", "다음역",
                "하차역", 3, null, now, null, null, null, null, null, now, now);
    }
}
