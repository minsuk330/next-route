package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 고른 열차가 아직 승차역 전인지(#469). 링의 다음 역 순번에서 가르고, 여정 ETA 만 쓴다 — 응답에는 싣지 않는다.
 */
class SubwayGuidanceTrainBeforeBoardingTest {

    private static final Instant T0 = Instant.parse("2026-10-21T12:28:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("링이 승차역(0)을 향하고 아직 서지 않았으면 승차역 전이다")
    void boardingSegmentIsBeforeBoarding() {
        SubwayGuidance guidance = SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선",
                ring(0, BigDecimal.ZERO));

        assertThat(guidance.trainBeforeBoarding()).isTrue();
    }

    @Test
    @DisplayName("링이 다음 구간(1)으로 넘어갔으면 승차역 전이 아니다 — 열차가 승차역에 섰거나 떠났다")
    void nextSegmentIsNotBeforeBoarding() {
        SubwayGuidance guidance = SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선",
                ring(1, BigDecimal.ZERO));

        assertThat(guidance.trainBeforeBoarding()).isFalse();
    }

    @Test
    @DisplayName("승차역 구간이어도 도착(진행률 1)이면 승차역 전이 아니다")
    void arrivedAtBoardingIsNotBeforeBoarding() {
        SubwayGuidance guidance = SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선",
                ring(0, BigDecimal.ONE));

        assertThat(guidance.trainBeforeBoarding()).isFalse();
    }

    @Test
    @DisplayName("열차를 아직 못 정했으면(링 없음) 승차역 전이라고 하지 않는다")
    void pendingIsNotBeforeBoarding() {
        assertThat(SubwayGuidance.pending(SubwayBindingStatus.PENDING, "서울2호선", "교대").trainBeforeBoarding())
                .isFalse();
    }

    @Test
    @DisplayName("응답 JSON 에는 실리지 않는다 — 모바일 계약은 그대로다")
    void notSerialized() {
        JsonNode json = objectMapper.valueToTree(SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "서울2호선",
                ring(0, BigDecimal.ZERO)));

        assertThat(json.has("trainBeforeBoarding")).isFalse();
        assertThat(json.has("estimatedAlightingAt")).isTrue();
    }

    private SubwayRingState ring(int nextStationSequence, BigDecimal progress) {
        return new SubwayRingState(1L, 1, 2, 0,
                nextStationSequence, "100000022" + nextStationSequence,
                nextStationSequence == 0 ? "강남" : "교대", "교대", 1,
                progress, T0, BigDecimal.ZERO, T0.plusSeconds(300), T0.plusSeconds(360),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0, 1L,
                T0.plusSeconds(450));
    }
}
