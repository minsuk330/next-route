package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.DirectedRailPattern;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("#335 노선 끝 역 — 종착 하차는 내리는 문을 내지 않는다")
class SubwayLineEndsTest {

    @Test
    @DisplayName("패턴 첫·끝이고 어디서도 중간 정차가 아니면 끝. 중간 회차역(사당행의 사당)은 다른 패턴의 중간이라 끝이 아니다")
    void endVsIntermediateTerminal() {
        List<DirectedRailPattern> patterns = List.of(
                pattern(false, "당고개", "사당", "금정", "오이도"),
                pattern(false, "당고개", "사당"),
                pattern(false, "오이도", "금정", "사당", "당고개"));

        assertThat(SubwayLineEnds.isLineEnd(patterns, "오이도")).contains(true);
        assertThat(SubwayLineEnds.isLineEnd(patterns, "당고개역")).contains(true);
        assertThat(SubwayLineEnds.isLineEnd(patterns, "사당")).contains(false);
        assertThat(SubwayLineEnds.isLineEnd(patterns, "금정")).contains(false);
    }

    @Test
    @DisplayName("순환 패턴은 첫·끝이 없다(2호선 성수), 패턴에 없는 역·패턴 없음은 모름")
    void loopAndUnknown() {
        assertThat(SubwayLineEnds.isLineEnd(List.of(pattern(true, "성수", "건대입구", "성수")), "성수")).contains(false);
        assertThat(SubwayLineEnds.isLineEnd(List.of(pattern(false, "가", "나")), "다")).isEmpty();
        assertThat(SubwayLineEnds.isLineEnd(List.of(), "가")).isEmpty();
    }

    @Test
    @DisplayName("캐시만 읽는다 — 비어 있으면 모름으로 답하고 뒤에서 적재를 건다")
    void readsCacheOnly() {
        SubwayLinePatternService service = mock(SubwayLinePatternService.class);
        given(service.cachedPatterns("1004", "하행")).willReturn(List.of(pattern(false, "당고개", "오이도")));
        SubwayLineEnds ends = new SubwayLineEnds(service);

        assertThat(ends.isLineEnd("1004", "오이도")).contains(true);
        verify(service).loadLater("1004", "상행");
        assertThat(ends.isLineEnd("1005", "광화문")).isEmpty();
    }

    private static DirectedRailPattern pattern(boolean loop, String... stops) {
        return new DirectedRailPattern("r", "r", loop, 1, Arrays.stream(stops)
                .map(name -> new RailPattern.Stop(name, 0, 0, null, null))
                .toList());
    }
}
