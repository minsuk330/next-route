package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayFastAlightIndex.FastAlight;
import watoo.grd.nextroute.application.subway.service.SubwayFastAlightIndex.Row;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#266 빠른 하차 칸·문")
class SubwayFastAlightIndexTest {

    private final SubwayFastAlightIndex index = SubwayFastAlightIndex.of(List.of(
            new Row(2, "시청", "충정로", "6-2", "ELEVATOR"),
            new Row(2, "시청", "충정로", "2-2", "STAIRS"),
            new Row(2, "시청", "을지로입구", "5-3", "ELEVATOR"),
            new Row(2, "시청", "을지로입구", "2-2", "STAIRS"),
            new Row(2, "시청", "을지로입구", "5-1", "STAIRS"),
            new Row(2, "시청", "을지로입구", "7-4", "STAIRS"),
            new Row(2, "시청", "을지로입구", "5-3", "STAIRS"),
            new Row(8, "암사", "천호", "1-1", "STAIRS"),
            new Row(2, "신도림", "문래", "1-1", "STAIRS"),
            new Row(2, "신도림", "대림", "2-1", "STAIRS"),
            new Row(2, "신도림", "도림천", "3-1", "STAIRS")));

    @Test
    @DisplayName("하차 직전 역이 아닌 쪽 행 — 충정로에서 와서 시청에 내리면 을지로입구 방향 행. 계단 먼저, 문 중복 제거, 최대 3")
    void picksTheOtherDirection() {
        assertThat(index.lookup(2, "시청(2호선)", "충정로")).containsExactly(
                new FastAlight("2-2", "STAIRS"), new FastAlight("5-1", "STAIRS"), new FastAlight("7-4", "STAIRS"));
        assertThat(index.lookup(2, "시청", "을지로입구")).containsExactly(
                new FastAlight("2-2", "STAIRS"), new FastAlight("6-2", "ELEVATOR"));
    }

    @Test
    @DisplayName("한 방향 행뿐인 역(종착)은 그것을 쓴다")
    void terminalSingleDirection() {
        assertThat(index.lookup(8, "암사", "천호")).containsExactly(new FastAlight("1-1", "STAIRS"));
    }

    @Test
    @DisplayName("방향을 못 정하면 빈 목록 — 직전 역을 모르거나 원천에 없음, 갈래역에서 남은 방향이 둘, 1~8호선 밖")
    void unknownDirectionIsEmpty() {
        assertThat(index.lookup(2, "시청", null)).isEmpty();
        assertThat(index.lookup(2, "시청", "용답")).isEmpty();
        assertThat(index.lookup(2, "신도림", "문래")).isEmpty();
        assertThat(index.lookup(9, "시청", "충정로")).isEmpty();
    }

    @Test
    @DisplayName("동봉한 원천 목록이 읽힌다 — 2호선 충정로→시청 하차")
    void bundledCsvLoads() {
        assertThat(new SubwayFastAlightIndex().lookup(2, "시청", "충정로"))
                .isNotEmpty()
                .allSatisfy(f -> assertThat(f.carDoor()).matches("\\d+-\\d+(~\\d+-\\d+)?"));
    }

    @Test
    @DisplayName("내부 line_id → 호선: 1001~1008 만")
    void lineIdMapping() {
        assertThat(SubwayFastAlightIndex.lineNoOfLineId("1002")).isEqualTo(2);
        assertThat(SubwayFastAlightIndex.lineNoOfLineId("1009")).isNull();
        assertThat(SubwayFastAlightIndex.lineNoOfLineId("1077")).isNull();
    }
}
