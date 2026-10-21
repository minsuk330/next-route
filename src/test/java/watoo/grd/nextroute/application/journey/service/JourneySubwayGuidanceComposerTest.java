package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import watoo.grd.nextroute.application.journey.dto.SubwayGuidance;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPhase;
import watoo.grd.nextroute.application.journey.dto.SubwayRingQuality;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingTrainKind;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * 역에서 고르고 아직 안 움직인 열차(#304)도 링이 있으면 안내에 싣는다. 이게 비면 앱이 Live Activity 를 못 띄운다.
 */
@DisplayName("지하철 안내 조립 — 사용자 선택")
class JourneySubwayGuidanceComposerTest {

    private static final Long JOURNEY = 1L;
    private static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");

    private final SubwayLegBindingRepository bindingRepository = mock(SubwayLegBindingRepository.class);
    private final SubwayRingPort ringPort = mock(SubwayRingPort.class);
    private final JourneySubwayGuidanceComposer composer =
            new JourneySubwayGuidanceComposer(bindingRepository, ringPort);

    private final JourneyLeg wait = leg(0, JourneyLegType.WAIT_SUBWAY);
    private final JourneyLeg ride = leg(1, JourneyLegType.RIDE_SUBWAY);

    @Test
    @DisplayName("이동 관측 전 사용자 선택 + 링 있음 → 다음 역·남은 역·진행률을 싣는다")
    void selectedBeforeMovementCarriesRing() {
        SubwayLegBinding binding = SubwayLegBinding.selected(ride.getId(), JOURNEY, 1, 1,
                "2390", LocalDate.parse("2026-10-07"), "1002", "내선");
        given(bindingRepository.findByJourneyLegIdIn(List.of(ride.getId()))).willReturn(List.of(binding));
        given(ringPort.find(JOURNEY, 1, 1, binding.getVersion())).willReturn(Optional.of(boardingRing(binding)));

        SubwayGuidance guidance = composer.compose(1, 0, List.of(wait, ride), Map.of());

        assertThat(binding.isSelectedBeforeMovement()).isTrue();
        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(guidance.nextStationName()).isEqualTo("강남");
        assertThat(guidance.remainingStations()).isEqualTo(3);
        assertThat(guidance.segmentProgress()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("링이 아직 없으면(첫 tick 전) 상태만 알리고 값은 비운다")
    void selectedWithoutRingIsPending() {
        SubwayLegBinding binding = SubwayLegBinding.selected(ride.getId(), JOURNEY, 1, 1,
                "2390", LocalDate.parse("2026-10-07"), "1002", "내선");
        given(bindingRepository.findByJourneyLegIdIn(List.of(ride.getId()))).willReturn(List.of(binding));
        given(ringPort.find(JOURNEY, 1, 1, binding.getVersion())).willReturn(Optional.empty());

        SubwayGuidance guidance = composer.compose(1, 0, List.of(wait, ride), Map.of());

        assertThat(guidance.bindingStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(guidance.nextStationName()).isNull();
        assertThat(guidance.quality()).isEqualTo(SubwayRingQuality.STALE);
    }

    @Test
    @DisplayName("같은 종류의 다른 급행으로 다시 고르면 trainKind 는 그대로여도 bindingVersion 이 오른다(#501)")
    void reselectSameKindBumpsBindingVersion() {
        SubwayLegBinding binding = SubwayLegBinding.selected(ride.getId(), JOURNEY, 1, 1,
                "1901", LocalDate.parse("2026-10-07"), "1001", "하행",
                SubwayBindingTrainKind.EXPRESS, "1001-gyeongbu-a", "2026-10-21");
        given(bindingRepository.findByJourneyLegIdIn(List.of(ride.getId()))).willReturn(List.of(binding));
        given(ringPort.find(JOURNEY, 1, 1, binding.getVersion())).willReturn(Optional.of(boardingRing(binding)));
        SubwayGuidance first = composer.compose(1, 0, List.of(wait, ride), Map.of());

        binding.selectByUser("1951", LocalDate.parse("2026-10-07"), "1001", "하행", null,
                SubwayBindingTrainKind.EXPRESS, "1001-gyeongbu-b", "2026-10-21");
        given(ringPort.find(JOURNEY, 1, 1, binding.getVersion())).willReturn(Optional.of(boardingRing(binding)));
        SubwayGuidance second = composer.compose(1, 0, List.of(wait, ride), Map.of());

        assertThat(first.bindingVersion()).isEqualTo(binding.getVersion() - 1);
        assertThat(second.bindingVersion()).isEqualTo(binding.getVersion());
    }

    @Test
    @DisplayName("링이 없어 확인 중이면 bindingVersion 은 싣지 않는다 — 앱이 노선 경로를 다시 받을 근거가 없다")
    void pendingHasNoBindingVersion() {
        SubwayLegBinding binding = SubwayLegBinding.selected(ride.getId(), JOURNEY, 1, 1,
                "2390", LocalDate.parse("2026-10-07"), "1002", "내선");
        given(bindingRepository.findByJourneyLegIdIn(List.of(ride.getId()))).willReturn(List.of(binding));
        given(ringPort.find(JOURNEY, 1, 1, binding.getVersion())).willReturn(Optional.empty());

        assertThat(composer.compose(1, 0, List.of(wait, ride), Map.of()).bindingVersion()).isNull();
    }

    @Test
    @DisplayName("실시간 없는 지하철(지방)은 남은 판정 행이 있어도 안내를 싣지 않는다 — \"확인 중\" 고착 방지")
    void regionalSubwayHasNoGuidance() {
        JourneyLeg regional = JourneyLeg.builder().journeyId(JOURNEY).routeRevision(1).legIndex(1)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.PENDING).build();
        ReflectionTestUtils.setField(regional, "id", 101L);

        assertThat(composer.compose(1, 0, List.of(wait, regional), Map.of())).isNull();
        verifyNoInteractions(bindingRepository, ringPort);
    }

    private SubwayRingState boardingRing(SubwayLegBinding binding) {
        return new SubwayRingState(JOURNEY, 1, 1, binding.getVersion(),
                0, "1002000222", "강남", "신도림", 3,
                BigDecimal.ZERO, T0, BigDecimal.ZERO, T0.plusSeconds(90), T0.plusSeconds(120),
                SubwayRingPhase.RIDE_SUBWAY, SubwayRingQuality.NORMAL, T0, T0);
    }

    private static JourneyLeg leg(int index, JourneyLegType type) {
        JourneyLeg leg = JourneyLeg.builder().journeyId(JOURNEY).routeRevision(1).legIndex(index)
                .type(type).state(JourneyLegState.PENDING).startStopId("0222").build();
        ReflectionTestUtils.setField(leg, "id", 100L + index);
        ReflectionTestUtils.setField(leg, "routeId", "1002");
        return leg;
    }
}
