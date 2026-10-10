package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.domain.subway.repository.SubwaySegmentRepository;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

class SubwayStationAdjacencyTest {

    private static final String LINE = "1001";
    private static final String UP = "상행";
    // 상행 진행: 병점(B) → 세류(Y) → 수원(S) → 화서(H)
    private static final String B = "1001080157", Y = "1001080156", S = "1001080155", H = "1001080154";

    private final SubwaySegmentRepository repository = mock(SubwaySegmentRepository.class);
    private final SubwayLineOrder lineOrder = mock(SubwayLineOrder.class);
    private final SubwayStationAdjacency adjacency = new SubwayStationAdjacency(repository, lineOrder);

    {
        given(repository.findAll()).willReturn(List.of());
        List<String> order = List.of(B, Y, S, H);
        given(lineOrder.nextOf(any(), any(), any())).willAnswer(inv -> {
            int i = order.indexOf(inv.<String>getArgument(2));
            return i >= 0 && i + 1 < order.size() ? Optional.of(order.get(i + 1)) : Optional.empty();
        });
        given(lineOrder.prevOf(any(), any(), any())).willAnswer(inv -> {
            int i = order.indexOf(inv.<String>getArgument(2));
            return i > 0 ? Optional.of(order.get(i - 1)) : Optional.empty();
        });
    }

    @Test
    @DisplayName("노선 순서에서 바로 앞뒤면 인접 — 진행 방향과 거꾸로 물어도 같다")
    void adjacentInOrder() {
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, S, H)).isTrue();
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, S, Y)).isTrue();
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, H, S)).isTrue();
    }

    @Test
    @DisplayName("한 역 건너뛰기·같은 역·모르는 값은 인접 아님")
    void notAdjacentInOrder() {
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, B, S)).isFalse();
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, S, S)).isFalse();
        assertThat(adjacency.isAdjacentInOrder(LINE, null, S, H)).isFalse();
        assertThat(adjacency.isAdjacentInOrder(LINE, UP, S, null)).isFalse();
    }

    @Test
    @DisplayName("노선 순서가 모르면(갈래 분기·패턴 적재 전) 인접 아님")
    void unknownOrderIsNotAdjacent() {
        given(lineOrder.nextOf(LINE, UP, B)).willReturn(Optional.empty());
        given(lineOrder.prevOf(LINE, UP, B)).willReturn(Optional.empty());

        assertThat(adjacency.isAdjacentInOrder(LINE, UP, B, Y)).isFalse();
    }
}
