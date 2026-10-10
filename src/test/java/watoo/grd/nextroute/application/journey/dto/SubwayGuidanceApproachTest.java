package watoo.grd.nextroute.application.journey.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 승차역 전 열차 위치(#368)가 스냅샷 {@code subwayGuidance} 에 push props 와 같은 이름으로 평평하게 나간다.
 * Redis 에 남은 옛 링(필드 없음)도 읽힌다.
 */
class SubwayGuidanceApproachTest {

    private static final Instant T0 = Instant.parse("2026-10-11T09:30:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("approachingStationName·stationsUntilBoarding·approachStations 로 실린다")
    void guidanceCarriesApproach() {
        SubwayRingState ring = ring().withApproach(new SubwayRingApproach("양재", 2, List.of("양재시민의숲", "양재")));

        JsonNode json = objectMapper.valueToTree(SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "신분당선", ring));

        assertThat(json.path("approachingStationName").asText()).isEqualTo("양재");
        assertThat(json.path("stationsUntilBoarding").asInt()).isEqualTo(2);
        assertThat(json.path("approachStations")).extracting(JsonNode::asText).containsExactly("양재시민의숲", "양재");
        assertThat(json.has("trainNo")).isFalse();
    }

    @Test
    @DisplayName("위치가 없으면 셋 다 null")
    void noApproachIsNull() {
        SubwayGuidance guidance = SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "신분당선", ring());

        assertThat(guidance.approachingStationName()).isNull();
        assertThat(guidance.stationsUntilBoarding()).isNull();
        assertThat(guidance.approachStations()).isNull();
        assertThat(SubwayGuidance.pending(SubwayBindingStatus.PENDING, "신분당선", "신사").approachStations()).isNull();
    }

    @Test
    @DisplayName("배포 전 Redis 값(approach 없음)도 읽힌다")
    void legacyRedisValueReads() throws Exception {
        ObjectNode stripped = objectMapper.valueToTree(ring());
        stripped.remove("approach");

        SubwayRingState read = objectMapper.treeToValue(stripped, SubwayRingState.class);

        assertThat(read.approach()).isNull();
        assertThat(read.nextStationName()).isEqualTo("강남");
    }

    @Test
    @DisplayName("링 상태 저장·읽기 왕복에서 위치가 유지된다")
    void roundTrip() throws Exception {
        SubwayRingState ring = ring().withApproach(new SubwayRingApproach("양재", 1, List.of("양재")));

        SubwayRingState read = objectMapper.readValue(objectMapper.writeValueAsString(ring), SubwayRingState.class);

        assertThat(read.approach()).isEqualTo(ring.approach());
    }

    private SubwayRingState ring() {
        return new SubwayRingState(1L, 1, 1, 0,
                0, "1077006801", "강남", "신사", 2,
                BigDecimal.ZERO, T0, BigDecimal.ZERO, null, T0.plusSeconds(60),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0, 1L);
    }
}
