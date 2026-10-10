package watoo.grd.nextroute.application.subway.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayEntranceIndex.Entrance;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#266 지하철 출구 고르기")
class SubwayEntranceIndexTest {

    // 가상 역 (37.5000, 127.0000). 출구 1 = 북동, 2 = 남서, 신논현 7 = 같은 반경 안 다른 역, 9 = 반경 밖
    private final SubwayEntranceIndex index = SubwayEntranceIndex.of(List.of(
            new Entrance(37.5010, 127.0012, "1", "강남역 1번 출구"),
            new Entrance(37.4990, 126.9988, "2", "강남역 2번 출구"),
            new Entrance(37.5002, 127.0015, "7", "신논현역 7번 출구"),
            new Entrance(37.5100, 127.0100, "9", "강남역 9번 출구")));

    @Test
    @DisplayName("역 반경 안 출구 중 목표에 가장 가까운 것")
    void nearestToTarget() {
        assertThat(index.exitToward("강남", 37.5, 127.0, 37.5030, 127.0030)).contains("1");
        assertThat(index.exitToward("강남", 37.5, 127.0, 37.4970, 126.9970)).contains("2");
    }

    @Test
    @DisplayName("이름표에 역 이름이 든 출구가 있으면 그것만 — 큰 환승역의 다른 역 출구를 집지 않는다")
    void prefersOwnStationLabel() {
        // 목표가 신논현 7번에 제일 가깝지만 강남 출구 중에서 고른다
        assertThat(index.exitToward("강남(2호선)", 37.5, 127.0, 37.5002, 127.0016)).contains("1");
        // 역 이름을 모르면 반경 안 전부
        assertThat(index.exitToward(null, 37.5, 127.0, 37.5002, 127.0016)).contains("7");
    }

    @Test
    @DisplayName("반경(350m) 밖 출구는 안 본다, 좌표를 모르면 empty")
    void radiusAndMissingCoords() {
        assertThat(SubwayEntranceIndex.of(List.of(new Entrance(37.5100, 127.0100, "9", "강남역 9번 출구")))
                .exitToward("강남", 37.5, 127.0, 37.51, 127.01)).isEmpty();
        assertThat(index.exitToward("강남", null, 127.0, 37.5, 127.0)).isEmpty();
    }

    @Test
    @DisplayName("동봉한 OSM 출입구 목록이 읽힌다 — 강남역(2호선) 10번 출구 쪽 목표면 10번")
    void bundledCsvLoads() {
        SubwayEntranceIndex real = new SubwayEntranceIndex();

        assertThat(real.exitToward("강남", 37.49795, 127.02763, 37.4987, 127.0268)).contains("10");
    }
}
