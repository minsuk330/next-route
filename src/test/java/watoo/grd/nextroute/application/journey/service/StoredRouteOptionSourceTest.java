package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.route.routing.RoutingDeadline;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Clock;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 저장 후보 출처 계약: optionIndex 오름차순 보존, 저장 조회는 항상 OK(빈 목록 포함). */
class StoredRouteOptionSourceTest {

    private final JourneyLegRouteOptionRepository repo = mock(JourneyLegRouteOptionRepository.class);
    private final StoredRouteOptionSource source = new StoredRouteOptionSource(repo);

    private JourneyLegRouteOption option(int optionIndex, String routeId) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(1L).optionIndex(optionIndex).laneIndex(optionIndex)
                .routeId(routeId).directionKey("UP").boardingSeq(1).build();
    }

    private JourneyLeg waitLeg() {
        JourneyLeg leg = mock(JourneyLeg.class);
        when(leg.getId()).thenReturn(1L);
        return leg;
    }

    @Test
    @DisplayName("optionIndex 오름차순으로 정렬해 OK 로 낸다")
    void sortsByOptionIndex() {
        when(repo.findByJourneyLegIdInAndDeletedAtIsNull(any()))
                .thenReturn(List.of(option(2, "BL-2"), option(0, "BL-0"), option(1, "BL-1")));

        var outcome = source.optionsFor(waitLeg(), RoutingDeadline.of(Clock.systemUTC(), 1000));

        assertThat(outcome.isOk()).isTrue();
        assertThat(outcome.options()).extracting(JourneyLegRouteOption::getRouteId)
                .containsExactly("BL-0", "BL-1", "BL-2");
    }

    @Test
    @DisplayName("저장 후보가 없어도 장애가 아니라 OK + 빈 목록")
    void emptyIsOkNotFailure() {
        when(repo.findByJourneyLegIdInAndDeletedAtIsNull(any())).thenReturn(List.of());

        var outcome = source.optionsFor(waitLeg(), RoutingDeadline.of(Clock.systemUTC(), 1000));

        assertThat(outcome.status()).isEqualTo(RerouteRouteOptionSource.SourceOutcome.Status.OK);
        assertThat(outcome.options()).isEmpty();
    }
}
