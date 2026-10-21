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

    @Test
    @DisplayName("단계의 열차 종류가 응답 trainKind 로 나간다 — 없으면 null(subway-express S2)")
    void trainKindToView() {
        watoo.grd.nextroute.domain.journey.entity.JourneyLeg express =
                watoo.grd.nextroute.domain.journey.entity.JourneyLeg.builder()
                        .journeyId(1L).routeRevision(1).legIndex(1)
                        .type(watoo.grd.nextroute.domain.journey.entity.JourneyLegType.RIDE_SUBWAY)
                        .state(watoo.grd.nextroute.domain.journey.entity.JourneyLegState.PENDING)
                        .startName("가양").endName("염창")
                        .guidance(watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance.EMPTY
                                .withTrainKind("EXPRESS").withAlightDoorSide("LEFT"))
                        .build();
        watoo.grd.nextroute.domain.journey.entity.JourneyLeg bus =
                watoo.grd.nextroute.domain.journey.entity.JourneyLeg.builder()
                        .journeyId(1L).routeRevision(1).legIndex(2)
                        .type(watoo.grd.nextroute.domain.journey.entity.JourneyLegType.RIDE_BUS)
                        .state(watoo.grd.nextroute.domain.journey.entity.JourneyLegState.PENDING)
                        .startName("a").endName("b")
                        .build();

        JourneyLegView view = JourneyQueryService.toView(express, new java.util.HashMap<>());

        assertThat(view.trainKind()).isEqualTo("EXPRESS");
        assertThat(view.alightDoorSide()).isEqualTo("LEFT");
        assertThat(JourneyQueryService.toView(bus, new java.util.HashMap<>()).trainKind()).isNull();
    }
}
