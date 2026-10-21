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
    @DisplayName("선택은 곧바로 확정이고 식별 4칸을 다 채운다. 이동 전이면 링은 승차역부터")
    void selectedIsConfirmedWithFullIdentity() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");

        assertThat(binding.getStatus()).isEqualTo(SubwayBindingStatus.CONFIRMED);
        assertThat(binding.getSource()).isEqualTo(SubwayBindingSource.USER_SELECTED);
        assertThat(binding.getTrainNo()).isEqualTo("2390");
        assertThat(binding.getTrainServiceDate()).isEqualTo(DAY);
        assertThat(binding.getTrainLineId()).isEqualTo("1002");
        assertThat(binding.getTrainDirection()).isEqualTo("내선");
        assertThat(binding.isSelectedBeforeMovement()).isTrue();

        binding.markBoardedDetected(Instant.parse("2026-10-07T03:00:00Z"));
        assertThat(binding.isSelectedBeforeMovement()).isFalse();
    }

    @Test
    @DisplayName("기존 행(자동 판정기 시절)에 선택: 확정도 덮고 버전을 올린다. 이동 순번은 그대로")
    void selectOverExisting() {
        SubwayLegBinding binding = legacyAuto(3);
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

        SubwayLegBinding auto = legacyAuto(1);
        assertThat(auto.inheritTo(newLeg).getSource()).isEqualTo(SubwayBindingSource.INHERITED_ON_REROUTE);
    }

    @Test
    @DisplayName("이미 탄 열차를 고르면(떠난 열차·탑승 중 재선택) 링이 승차역으로 되감기지 않는다")
    void aboardSelectionIsNotBeforeMovement() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");
        binding.selectByUser("2386", DAY, "1002", "내선", Instant.parse("2026-10-07T03:00:00Z"));
        assertThat(binding.isSelectedBeforeMovement()).isFalse();

        // 다시 곧 올 열차로 바꾸면(승강장) 승차역부터
        binding.selectByUser("2392", DAY, "1002", "내선", null);
        assertThat(binding.isSelectedBeforeMovement()).isTrue();
    }


    // ── 열차 종류(subway-express PR-S4, V72) ─────────────────────────────────

    @Test
    @DisplayName("V72: 고른 열차의 종류·급행 서비스·카탈로그 판을 싣고, 재탐색 계승이 그대로 옮긴다")
    void trainKindIsStoredAndInherited() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "9600", DAY, "1009", "상행",
                SubwayBindingTrainKind.EXPRESS, "1009-express", "2026-10-21");
        assertThat(binding.getTrainKind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(binding.getTrainServiceId()).isEqualTo("1009-express");
        assertThat(binding.getCatalogVersion()).isEqualTo("2026-10-21");

        JourneyLeg newLeg = JourneyLeg.builder().journeyId(1L).routeRevision(2).legIndex(3)
                .type(JourneyLegType.RIDE_SUBWAY).state(JourneyLegState.PENDING).build();
        ReflectionTestUtils.setField(newLeg, "id", 20L);
        SubwayLegBinding inherited = binding.inheritTo(newLeg);
        assertThat(inherited.getTrainKind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(inherited.getTrainServiceId()).isEqualTo("1009-express");
        assertThat(inherited.getCatalogVersion()).isEqualTo("2026-10-21");
    }

    @Test
    @DisplayName("다른 열차를 고르면 종류를 늘 덮는다 — 종류 없이 고르면 미분류(null), 앞 급행 서비스가 남지 않는다")
    void reselectOverwritesKind() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "9600", DAY, "1009", "상행",
                SubwayBindingTrainKind.EXPRESS, "1009-express", "2026-10-21");

        binding.selectByUser("9100", DAY, "1009", "상행", null, SubwayBindingTrainKind.NORMAL, null, null);
        assertThat(binding.getTrainKind()).isEqualTo(SubwayBindingTrainKind.NORMAL);
        assertThat(binding.getTrainServiceId()).isNull();

        binding.selectByUser("9602", DAY, "1009", "상행");
        assertThat(binding.getTrainKind()).isNull();
        assertThat(binding.getCatalogVersion()).isNull();
    }

    @Test
    @DisplayName("급행인데 서비스가 없으면 미분류로 둔다(V72 CHECK 를 어기지 않는다)")
    void expressWithoutServiceIsUnclassified() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "9600", DAY, "1009", "상행",
                SubwayBindingTrainKind.EXPRESS, null, "2026-10-21");

        assertThat(binding.getTrainKind()).isNull();
        assertThat(binding.getCatalogVersion()).isNull();
    }

    @Test
    @DisplayName("배포 전 행의 지연 분류: null 일 때만 한 번, 버전은 그대로(링 키 불변)")
    void classifyOnlyWhenUnclassified() {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "9600", DAY, "1009", "상행");
        int version = binding.getVersion();

        assertThat(binding.classifyIfUnclassified(SubwayBindingTrainKind.EXPRESS, null, null)).isFalse();
        assertThat(binding.classifyIfUnclassified(SubwayBindingTrainKind.EXPRESS, "1009-express", "2026-10-21"))
                .isTrue();
        assertThat(binding.classifyIfUnclassified(SubwayBindingTrainKind.NORMAL, null, null)).isFalse();
        assertThat(binding.getTrainKind()).isEqualTo(SubwayBindingTrainKind.EXPRESS);
        assertThat(binding.getVersion()).isEqualTo(version);
    }

    /** 자동 판정기(2026-10 제거)가 남긴 행 — 모션 출처·잠정·이동 순번. 이제 코드로는 못 만들어 칸을 직접 채운다. */
    private static SubwayLegBinding legacyAuto(int movementSeq) {
        SubwayLegBinding binding = SubwayLegBinding.selected(10L, 1L, 1, 1, "2390", DAY, "1002", "내선");
        ReflectionTestUtils.setField(binding, "status", SubwayBindingStatus.PROVISIONAL);
        ReflectionTestUtils.setField(binding, "source", SubwayBindingSource.MOTION_AUTOMOTIVE);
        ReflectionTestUtils.setField(binding, "confidence", SubwayBindingConfidence.HIGH);
        ReflectionTestUtils.setField(binding, "lastMovementSeq", movementSeq);
        return binding;
    }
}
