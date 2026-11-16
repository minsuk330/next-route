package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#593 여정 단계의 원천(OTP) 정류장 id → 응답")
class JourneyQueryServiceSourceStopIdTest {

    private static JourneyLeg leg(JourneyLegType type, String startStopId, String endStopId,
                                  String sourceStart, String sourceEnd) {
        return JourneyLeg.builder()
                .journeyId(495L).routeRevision(1).legIndex(0).type(type).state(JourneyLegState.ACTIVE)
                .startStopId(startStopId).endStopId(endStopId)
                .sourceStartStopId(sourceStart).sourceEndStopId(sourceEnd)
                .startName("서면(부산2호선)").endName("해운대")
                .guidance(JourneyLegGuidance.EMPTY)
                .build();
    }

    @Test
    @DisplayName("지방 지하철: 실시간 id 는 비고, 원천 id 가 검색 응답 stationID 와 같은 OTP id 로 나간다")
    void regionalSubwayCarriesOtpIds() throws Exception {
        JourneyLegView wait = JourneyQueryService.toView(leg(JourneyLegType.WAIT_SUBWAY, null, null,
                "1:RS_ACC1_S-2-0219", "1:RS_ACC1_S-2-0219"), new HashMap<>());
        JourneyLegView ride = JourneyQueryService.toView(leg(JourneyLegType.RIDE_SUBWAY, null, null,
                "1:RS_ACC1_S-2-0219", "1:RS_ACC1_S-2-0203"), new HashMap<>());

        assertThat(wait.startStopId()).isNull();
        assertThat(wait.sourceStartStopId()).isEqualTo("1:RS_ACC1_S-2-0219");
        assertThat(ride.sourceEndStopId()).isEqualTo("1:RS_ACC1_S-2-0203");

        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(ride));
        assertThat(json.path("sourceStartStopId").asText()).isEqualTo("1:RS_ACC1_S-2-0219");
        assertThat(json.path("sourceEndStopId").asText()).isEqualTo("1:RS_ACC1_S-2-0203");
    }

    @Test
    @DisplayName("서울 구간: startStopId 는 지금처럼 실시간 id 그대로(원천 id 는 따로 실린다)")
    void seoulKeepsRealtimeIds() {
        JourneyLegView wait = JourneyQueryService.toView(leg(JourneyLegType.WAIT_SUBWAY, "1002000222", "1002000222",
                "1:RS_ACC1_S-1-0222", "1:RS_ACC1_S-1-0222"), new HashMap<>());

        assertThat(wait.startStopId()).isEqualTo("1002000222");
        assertThat(wait.sourceStartStopId()).isEqualTo("1:RS_ACC1_S-1-0222");
    }
}
