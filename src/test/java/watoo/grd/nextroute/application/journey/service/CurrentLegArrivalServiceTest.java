package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.config.JourneyNavigationProperties;
import watoo.grd.nextroute.application.journey.dto.CurrentLegArrival;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("단계 종류에 맞는 계산 방법을 고른다")
class CurrentLegArrivalServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-04T09:10:00Z");

    private final CurrentLegArrivalService service = new CurrentLegArrivalService(
            List.of(new ClientGeometryProgressArrivalPredictor(
                    new StaticGeometryProgressEtaCalculator(), new JourneyNavigationProperties())),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    @DisplayName("맡는 구현이 등록되지 않은 단계는 모른다고 말한다")
    void unsupportedLegIsUnknown() {
        // 이 테스트는 등록된 구현만으로 조립한다. 실제 컨텍스트에는 대기 단계 구현이 따로 있다.
        CurrentLegArrival arrival = service.predict(leg(JourneyLegType.WAIT_BUS), null);

        assertThat(arrival.isUnknown()).isTrue();
        assertThat(arrival.arrivalAt()).isNull();
    }

    @Test
    @DisplayName("등록되지 않은 다른 단계도 마찬가지다")
    void anotherUnsupportedLegIsUnknown() {
        assertThat(service.predict(leg(JourneyLegType.WAIT_SUBWAY), null).isUnknown()).isTrue();
    }

    @Test
    @DisplayName("이동 단계는 맡는 구현으로 넘어간다")
    void movingLegIsDelegated() {
        JourneyLeg leg = leg(JourneyLegType.WALK);
        ReflectionTestUtils.setField(leg, "activatedAt", NOW.minusSeconds(60));

        CurrentLegArrival arrival = service.predict(leg, null);

        assertThat(arrival.isUnknown()).isFalse();
        assertThat(arrival.source()).isEqualTo(CurrentLegArrival.Source.PLANNED_DURATION);
    }

    private JourneyLeg leg(JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder()
                .journeyId(1L)
                .routeRevision(1)
                .legIndex(0)
                .type(type)
                .state(JourneyLegState.ACTIVE)
                .plannedDurationSec(600)
                .build();
        ReflectionTestUtils.setField(leg, "id", 10L);
        return leg;
    }
}
