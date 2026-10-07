package watoo.grd.nextroute.domain.journey.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("고정 행 — 사용자 선택")
class SubwayLegBindingSelectionTest {

    private static final LocalDate DAY = LocalDate.parse("2026-10-07");

    @Test
    @DisplayName("선택은 곧바로 확정이고 식별 4칸을 다 채운다. 이동 전이라 링 대상 아님")
    void selectedIsConfirmedWithFullIdentity() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");

        assertThat(binding.getStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(binding.getSource()).isEqualTo(SubwayBindingSource.USER_SELECTED);
        assertThat(binding.getTrainNo()).isEqualTo("2390");
        assertThat(binding.getTrainServiceDate()).isEqualTo(DAY);
        assertThat(binding.getTrainLineId()).isEqualTo("1002");
        assertThat(binding.getTrainDirection()).isEqualTo("내선");
        assertThat(binding.isSelectedBeforeMovement()).isTrue();

        binding.advanceMovementSeq(1);
        assertThat(binding.isSelectedBeforeMovement()).isFalse();
    }

    @Test
    @DisplayName("기존 행에 선택: 확정도 덮고 버전을 올린다. 이동 순번은 그대로")
    void selectOverExisting() {
        SubwayLegBinding binding = SubwayLegBinding.pending(10L, 1L, 1, 1, Instant.now(),
                SubwayBindingSource.MOTION_AUTOMOTIVE, SubwayBindingConfidence.HIGH);
        binding.advanceMovementSeq(3);
        int before = binding.getVersion();

        binding.selectByUser("2392", DAY, "1002", "외선");

        assertThat(binding.getStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(binding.getSource()).isEqualTo(SubwayBindingSource.USER_SELECTED);
        assertThat(binding.getConfidence()).isNull();
        assertThat(binding.getLastMovementSeq()).isEqualTo(3);
        assertThat(binding.getVersion()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("감지 시각은 처음 한 번만 채운다")
    void boardedDetectedOnce() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");
        Instant first = Instant.parse("2026-10-07T03:00:00Z");

        binding.markBoardedDetected(first);
        binding.markBoardedDetected(first.plusSeconds(60));

        assertThat(binding.getBoardedDetectedAt()).isEqualTo(first);
    }

    @Test
    @DisplayName("재탐색 계승: 사용자 선택은 출처를 지키고, 자동 판정은 INHERITED_ON_REROUTE")
    void inheritKeepsUserSelected() {
        JourneyLeg newLeg = JourneyLeg.builder().journeyId(1L).routeRevision(2).legIndex(3)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.PENDING).build();
        ReflectionTestUtils.setField(newLeg, "id", 20L);

        SubwayLegBinding selected = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");
        assertThat(selected.inheritTo(newLeg).getSource()).isEqualTo(SubwayBindingSource.USER_SELECTED);

        SubwayLegBinding auto = SubwayLegBinding.pending(10L, 1L, 1, 1, Instant.now(),
                SubwayBindingSource.MOTION_AUTOMOTIVE, SubwayBindingConfidence.HIGH);
        assertThat(auto.inheritTo(newLeg).getSource()).isEqualTo(SubwayBindingSource.INHERITED_ON_REROUTE);
    }
}
