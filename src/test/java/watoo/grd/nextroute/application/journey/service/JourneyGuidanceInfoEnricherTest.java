package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.domain.bus.entity.BusStop;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegGuidance;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@DisplayName("#266 안내 값 — 서울 버스 정류장 번호")
class JourneyGuidanceInfoEnricherTest {

    private final BusStopRepository repository = mock(BusStopRepository.class);
    private final JourneyGuidanceInfoEnricher enricher = new JourneyGuidanceInfoEnricher(repository);

    @Test
    @DisplayName("대기 단계는 승차 정류장 번호 둘 다, 탑승 단계는 승차·하차 번호. 내부 정류장 ID 로 찾는다")
    void fillsSeoulArsByResolvedStopId() {
        List<BusStop> stops = List.of(stop("104000136", "05229"), stop("104000170", "05263"));
        given(repository.findByStopIdIn(any())).willReturn(stops);
        JourneyPlan plan = new JourneyPlan(List.of(
                leg(0, JourneyLegType.WAIT_BUS, "104000136", "104000136"),
                leg(1, JourneyLegType.RIDE_BUS, "104000136", "104000170"),
                leg(2, JourneyLegType.WALK, null, null)), List.of());

        List<JourneyLegDraft> legs = enricher.enrich(plan).legs();

        assertThat(legs.get(0).guidance()).extracting(JourneyLegGuidance::startStopNo, JourneyLegGuidance::endStopNo)
                .containsExactly("05229", "05229");
        assertThat(legs.get(1).guidance()).extracting(JourneyLegGuidance::startStopNo, JourneyLegGuidance::endStopNo)
                .containsExactly("05229", "05263");
        assertThat(legs.get(2).guidance()).isEqualTo(JourneyLegGuidance.EMPTY);
    }

    @Test
    @DisplayName("서울 정류장에 없거나(경기·지방) 번호가 비면 null — 방면 같은 다른 값은 보존")
    void unknownStopsStayNullAndKeepOtherValues() {
        List<BusStop> stops = List.of(stop("104000136", " "));
        given(repository.findByStopIdIn(any())).willReturn(stops);
        JourneyLegDraft ride = leg(1, JourneyLegType.RIDE_BUS, "104000136", "ICB164000396")
                .withGuidance(JourneyLegGuidance.EMPTY.withDirectionName("보존"));

        JourneyLegDraft out = enricher.enrich(new JourneyPlan(List.of(ride), List.of())).legs().get(0);

        assertThat(out.guidance().startStopNo()).isNull();
        assertThat(out.guidance().endStopNo()).isNull();
        assertThat(out.guidance().directionName()).isEqualTo("보존");
    }

    @Test
    @DisplayName("조회가 실패해도 여정 시작을 막지 않는다 — 번호 없이 그대로")
    void lookupFailureKeepsPlan() {
        given(repository.findByStopIdIn(any())).willThrow(new IllegalStateException("db down"));
        JourneyPlan plan = new JourneyPlan(List.of(leg(0, JourneyLegType.WAIT_BUS, "104000136", "104000136")), List.of());

        assertThat(enricher.enrich(plan).legs().get(0).guidance()).isEqualTo(JourneyLegGuidance.EMPTY);
    }

    @Test
    @DisplayName("버스 단계가 없으면 조회하지 않는다")
    void noBusNoQuery() {
        enricher.enrich(new JourneyPlan(List.of(leg(0, JourneyLegType.WAIT_SUBWAY, "1002000222", "1002000222")),
                List.of()));

        verify(repository, never()).findByStopIdIn(any());
    }

    private static JourneyLegDraft leg(int index, JourneyLegType type, String start, String end) {
        return new JourneyLegDraft(index, type, JourneyLegState.PENDING, start, end, null, null,
                "a", "b", null, null, null, null, null, null, List.of(), null);
    }

    private static BusStop stop(String stopId, String arsId) {
        BusStop stop = mock(BusStop.class);
        given(stop.getStopId()).willReturn(stopId);
        given(stop.getArsId()).willReturn(arsId);
        return stop;
    }
}
