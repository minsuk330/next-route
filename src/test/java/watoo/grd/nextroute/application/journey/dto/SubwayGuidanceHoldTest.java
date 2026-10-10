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
 * 멈춤 이유가 스냅샷 {@code subwayGuidance} 로 push props 와 같은 이름으로 나간다.
 * Redis 에 남은 옛 링(필드 없음)도 그대로 읽힌다.
 */
class SubwayGuidanceHoldTest {

    private static final Instant T0 = Instant.parse("2026-10-10T09:30:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("링의 멈춤 이유·정차 역이 스냅샷에 holdReason·holdStationName 으로 실린다")
    void guidanceCarriesHold() throws Exception {
        SubwayRingState ring = ring().withHold(SubwayRingHoldReason.PREV_STATION_STOPPED, "신사");

        JsonNode json = objectMapper.valueToTree(SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "3호선", ring));

        assertThat(json.path("holdReason").asText()).isEqualTo("PREV_STATION_STOPPED");
        assertThat(json.path("holdStationName").asText()).isEqualTo("신사");
    }

    @Test
    @DisplayName("멈추지 않은 링·미확정 안내는 둘 다 null")
    void noHoldIsNull() {
        assertThat(SubwayGuidance.of(SubwayBindingStatus.CONFIRMED, "3호선", ring()).holdReason()).isNull();
        assertThat(SubwayGuidance.pending(SubwayBindingStatus.PENDING, "3호선", "경복궁").holdStationName()).isNull();
    }

    @Test
    @DisplayName("배포 전 Redis 값(멈춤 필드 없음)도 읽힌다 — 이유는 null")
    void legacyRedisValueReads() throws Exception {
        String legacy = objectMapper.writeValueAsString(ring());
        JsonNode stripped = objectMapper.readTree(legacy);
        ((com.fasterxml.jackson.databind.node.ObjectNode) stripped).remove("holdReason");
        ((com.fasterxml.jackson.databind.node.ObjectNode) stripped).remove("holdStationName");

        SubwayRingState read = objectMapper.treeToValue(stripped, SubwayRingState.class);

        assertThat(read.holdReason()).isNull();
        assertThat(read.nextStationName()).isEqualTo("옥수");
    }

    private SubwayRingState ring() {
        return new SubwayRingState(1L, 1, 1, 0,
                3, "1003000322", "옥수", "경복궁", 6,
                BigDecimal.ZERO, T0, BigDecimal.ZERO, null, T0.plusSeconds(60),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0, 1L);
    }
}
