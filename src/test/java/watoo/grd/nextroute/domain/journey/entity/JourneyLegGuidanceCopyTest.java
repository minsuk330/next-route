package watoo.grd.nextroute.domain.journey.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#266 안내 값 — 재탐색 수락의 복제·자르기")
class JourneyLegGuidanceCopyTest {

    private static final JourneyLegGuidance ORIGINAL = new JourneyLegGuidance(
            "05229", "05263", "교대", "3", "10", "[{\"carDoor\":\"2-3\",\"facility\":\"STAIRS\"}]", "LEFT");

    @Test
    @DisplayName("앞 구간 복제는 안내 값을 그대로 가져간다")
    void copyKeepsGuidance() {
        JourneyLeg copy = leg().copyToRevision(2);

        assertThat(copy.getGuidance()).isEqualTo(ORIGINAL);
    }

    @Test
    @DisplayName("자르기는 하차 쪽(정류장 번호·출구·빠른 하차·내리는 문)만 새 하차점 값으로, 승차 쪽·방면은 그대로")
    void truncateReplacesEndSide() {
        JourneyLegGuidance newEnd = new JourneyLegGuidance("99999", "05240", "무시", "무시", "4", null, "RIGHT");

        JourneyLeg copy = leg().copyToRevisionTruncated(2, new JourneyLeg.Truncation(
                "104000150", "104000150", "새 하차", 300, Instant.EPOCH, 3, 200, BigDecimal.ZERO, newEnd));

        assertThat(copy.getGuidance())
                .isEqualTo(new JourneyLegGuidance("05229", "05240", "교대", "3", "4", null, "RIGHT"));
    }

    @Test
    @DisplayName("옛 8인자 Truncation 은 하차 쪽을 비운다 — 옛 하차점 값을 들고 가지 않는다")
    void legacyTruncationClearsEndSide() {
        JourneyLeg copy = leg().copyToRevisionTruncated(2, new JourneyLeg.Truncation(
                "104000150", "104000150", "새 하차", 300, Instant.EPOCH, 3, 200, BigDecimal.ZERO));

        assertThat(copy.getGuidance().endStopNo()).isNull();
        assertThat(copy.getGuidance().endExitNo()).isNull();
        assertThat(copy.getGuidance().fastAlightJson()).isNull();
        assertThat(copy.getGuidance().alightDoorSide()).isNull();
        assertThat(copy.getGuidance().startStopNo()).isEqualTo("05229");
    }

    private static JourneyLeg leg() {
        return JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_BUS).state(JourneyLegState.ACTIVE)
                .startStopId("104000136").endStopId("104000170")
                .startName("a").endName("b")
                .guidance(ORIGINAL)
                .build();
    }
}
