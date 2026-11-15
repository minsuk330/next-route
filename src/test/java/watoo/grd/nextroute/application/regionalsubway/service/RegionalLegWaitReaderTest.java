package watoo.grd.nextroute.application.regionalsubway.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.regionalsubway.config.RegionalSubwayServingProperties;
import watoo.grd.nextroute.application.regionalsubway.dto.RegionalDeparturesView;
import watoo.grd.nextroute.application.regionalsubway.port.out.RegionalDepartureReadPort.Row;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 여정 대기 단계 → 지방 공식 시간표. 타는 역 = 대기 단계 sourceStartStopId, 내릴 역 = 다음 RIDE_SUBWAY 의 sourceEndStopId. */
class RegionalLegWaitReaderTest {

    private final RegionalStationSeedCatalog catalog = new RegionalStationSeedCatalog(new ObjectMapper());
    private final String seomyeon = catalog.findByName("BS1", "서면").gtfsStopId();
    private final String dadaepo = catalog.findByName("BS1", "다대포해수욕장").gtfsStopId();
    private final RegionalDepartureQueryService query = new RegionalDepartureQueryService(catalog,
            (key, day) -> key.equals("BS1-119")
                    ? List.of(new Row(8 * 3600 + 3 * 60, "다대포해수욕장", catalog.findByName("BS1", "다대포해수욕장").seq()),
                              new Row(8 * 3600 + 5 * 60, "노포", 0))
                    : List.of(),
            date -> false);
    private final RegionalSubwayServingProperties serving = new RegionalSubwayServingProperties();
    private final JourneyLegRepository repo = mock(JourneyLegRepository.class);
    private final RegionalLegWaitReader reader = new RegionalLegWaitReader(query, catalog, serving, repo);
    private final Instant mon0800 = Instant.parse("2026-11-15T23:00:00Z");

    private JourneyLeg leg(JourneyLegType type, int index, String sourceStart, String sourceEnd) {
        JourneyLeg l = mock(JourneyLeg.class);
        given(l.getType()).willReturn(type);
        given(l.getLegIndex()).willReturn(index);
        given(l.getJourneyId()).willReturn(7L);
        given(l.getRouteRevision()).willReturn(1);
        given(l.getSourceStartStopId()).willReturn(sourceStart);
        given(l.getSourceEndStopId()).willReturn(sourceEnd);
        return l;
    }

    @Test
    @DisplayName("서면 → 다대포해수욕장 구간: 다음 RIDE_SUBWAY 의 내릴 역 방향 첫 열차(08:03)")
    void nextDepartureTowardAlight() {
        serving.setEnabled(true);
        JourneyLeg wait = leg(JourneyLegType.WAIT_SUBWAY, 2, seomyeon, seomyeon);
        JourneyLeg ride = leg(JourneyLegType.RIDE_SUBWAY, 3, seomyeon, dadaepo);
        given(repo.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(anyLong(), anyInt()))
                .willReturn(List.of(wait, ride));

        Optional<RegionalDeparturesView.Departure> d = reader.nextDeparture(wait, mon0800);

        assertThat(d).hasValueSatisfying(x -> {
            assertThat(x.terminalName()).isEqualTo("다대포해수욕장");
            assertThat(x.scheduledAt()).isEqualTo(Instant.parse("2026-11-15T23:03:00Z"));
        });
    }

    @Test
    @DisplayName("서울 정류장이면 DB 를 읽지 않고 empty, serving off 도 empty")
    void seoulOrServingOff() {
        serving.setEnabled(true);
        assertThat(reader.nextDeparture(leg(JourneyLegType.WAIT_SUBWAY, 2, "1:RS_ACC1_S-1-0150", null), mon0800)).isEmpty();
        verifyNoInteractions(repo);

        serving.setEnabled(false);
        assertThat(reader.nextDeparture(leg(JourneyLegType.WAIT_SUBWAY, 2, seomyeon, null), mon0800)).isEmpty();
    }
}
