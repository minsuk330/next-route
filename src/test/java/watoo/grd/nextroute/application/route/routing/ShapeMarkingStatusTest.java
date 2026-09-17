package watoo.grd.nextroute.application.route.routing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** PR-B2 배포 게이트 계약: 기동 시 형상 마킹 유효 상태 판정. */
class ShapeMarkingStatusTest {

    private ShapeCoverageIndex index(String graphVersion) {
        return new ShapeCoverageIndex(graphVersion, Set.of("bus"), Set.of("BR_X_Ord001"));
    }

    @Test
    @DisplayName("shapesEnabled=false → DISABLED (인덱스 상태 무관)")
    void disabledWhenFlagOff() {
        assertThat(index("g1").markingStatus(false, "g1")).isEqualTo(ShapeMarkingStatus.DISABLED);
        assertThat(ShapeCoverageIndex.disabled().markingStatus(false, "g1"))
                .isEqualTo(ShapeMarkingStatus.DISABLED);
    }

    @Test
    @DisplayName("enabled + 인덱스 부재/disabled → NO_INDEX")
    void noIndexWhenDisabledIndex() {
        assertThat(ShapeCoverageIndex.disabled().markingStatus(true, "g1"))
                .isEqualTo(ShapeMarkingStatus.NO_INDEX);
    }

    @Test
    @DisplayName("enabled + graphVersion 불일치 → GRAPH_VERSION_MISMATCH (잘못된 배포 조합)")
    void mismatchWhenGraphVersionDiffers() {
        assertThat(index("g1").markingStatus(true, "g2"))
                .isEqualTo(ShapeMarkingStatus.GRAPH_VERSION_MISMATCH);
        assertThat(index("g1").markingStatus(true, null))
                .isEqualTo(ShapeMarkingStatus.GRAPH_VERSION_MISMATCH);
    }

    @Test
    @DisplayName("enabled + graphVersion 일치 → ACTIVE (유일하게 SHAPE_OK 발행 가능)")
    void activeWhenAligned() {
        ShapeMarkingStatus status = index("g1").markingStatus(true, "g1");
        assertThat(status).isEqualTo(ShapeMarkingStatus.ACTIVE);
        assertThat(status.isActive()).isTrue();
        assertThat(ShapeMarkingStatus.DISABLED.isActive()).isFalse();
        assertThat(ShapeMarkingStatus.GRAPH_VERSION_MISMATCH.isActive()).isFalse();
    }
}
