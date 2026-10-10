package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#266 빠른 하차 저장값 → 응답")
class JourneyQueryServiceFastAlightTest {

    @Test
    @DisplayName("저장된 JSON 배열을 칸·문·시설로 푼다")
    void parses() {
        assertThat(JourneyQueryService.fastAlight(
                "[{\"carDoor\":\"2-3\",\"facility\":\"STAIRS\"},{\"carDoor\":\"9-2\",\"facility\":\"ELEVATOR\"}]"))
                .containsExactly(new JourneyLegView.FastAlight("2-3", "STAIRS"),
                        new JourneyLegView.FastAlight("9-2", "ELEVATOR"));
    }

    @Test
    @DisplayName("없거나 깨진 값은 null — 안내 줄 하나 때문에 여정 조회가 실패하지 않는다")
    void missingOrBrokenIsNull() {
        assertThat(JourneyQueryService.fastAlight(null)).isNull();
        assertThat(JourneyQueryService.fastAlight(" ")).isNull();
        assertThat(JourneyQueryService.fastAlight("{not json")).isNull();
    }
}
