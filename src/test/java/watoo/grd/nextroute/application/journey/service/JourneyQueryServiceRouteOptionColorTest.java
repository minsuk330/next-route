package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#607 여정 노선 후보의 노선색 → 응답")
class JourneyQueryServiceRouteOptionColorTest {

    private static final long LEG_ID = 7L;

    private static JourneyLeg waitLeg() {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(1L).routeRevision(1).legIndex(0).type(JourneyLegType.WAIT_BUS)
                .state(JourneyLegState.ACTIVE).startName("정류장").guidance(JourneyLegGuidance.EMPTY)
                .build();
        ReflectionTestUtils.setField(leg, "id", LEG_ID);
        return leg;
    }

    private static JourneyLegRouteOption option(int index, String name, String color) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(LEG_ID).optionIndex(index).laneIndex(index)
                .sourceRouteId("BL-" + name).routeName(name).routeDisplayColor(color)
                .build();
    }

    @Test
    @DisplayName("묶인 노선마다 저장된 색이 그대로 나가고, 색이 생기기 전 행은 null")
    void eachOptionCarriesItsColor() throws Exception {
        JourneyLegView view = JourneyQueryService.toView(waitLeg(), Map.of(LEG_ID, List.of(
                option(1, "3201", "#00A84D"), option(0, "140", "#3D5BAB"), option(2, "741", null))));

        assertThat(view.routeOptions())
                .extracting(o -> o.routeName() + "=" + o.routeDisplayColor())
                .containsExactly("140=#3D5BAB", "3201=#00A84D", "741=null");

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(view));
        assertThat(json.path("routeOptions").get(0).path("routeDisplayColor").asText()).isEqualTo("#3D5BAB");
        assertThat(json.path("routeOptions").get(2).has("routeDisplayColor")).isTrue();
        assertThat(json.path("routeOptions").get(2).path("routeDisplayColor").isNull()).isTrue();
    }
}
