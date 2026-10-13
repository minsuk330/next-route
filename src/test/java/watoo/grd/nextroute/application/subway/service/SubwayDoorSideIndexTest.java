package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex.PlatformType;
import watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex.Row;
import watoo.grd.nextroute.application.subway.service.SubwayDoorSideIndex.Side;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("#335 내리는 문 방향 — 승강장 형식 × 통행 방향")
class SubwayDoorSideIndexTest {

    @Test
    @DisplayName("우측 통행(2호선): 섬식=왼쪽, 상대식=오른쪽. 좌측 통행(경의중앙): 섬식=오른쪽, 상대식=왼쪽")
    void fourCombinations() {
        SubwayDoorSideIndex index = SubwayDoorSideIndex.of(List.of(
                new Row("S1", "2", "섬역", PlatformType.ISLAND),
                new Row("S1", "2", "상대역", PlatformType.SIDE),
                new Row("KR", "K4", "섬역", PlatformType.ISLAND),
                new Row("KR", "K4", "상대역", PlatformType.SIDE)));

        assertThat(index.lookup("1002", "섬역")).contains(Side.LEFT);
        assertThat(index.lookup("1002", "상대역")).contains(Side.RIGHT);
        assertThat(index.lookup("1063", "섬역")).contains(Side.RIGHT);
        assertThat(index.lookup("1063", "상대역")).contains(Side.LEFT);
    }

    @Test
    @DisplayName("복합 승강장·표 밖 노선(공항철도)·모르는 역·같은 이름 둘은 값을 내지 않는다")
    void emptyWhenNotDeterminable() {
        SubwayDoorSideIndex index = SubwayDoorSideIndex.of(List.of(
                new Row("S1", "5", "복합역", PlatformType.OTHER),
                new Row("AR", "A1", "공덕", PlatformType.SIDE),
                new Row("S1", "1", "겹친역", PlatformType.SIDE),
                new Row("KR", "1", "겹친역", PlatformType.SIDE)));

        assertThat(index.lookup("1005", "복합역")).isEmpty();
        assertThat(index.lookup("1065", "공덕")).isEmpty();
        assertThat(index.lookup("1005", "없는역")).isEmpty();
        assertThat(index.lookup("1001", "겹친역")).isEmpty();
        assertThat(index.lookup(null, "공덕")).isEmpty();
        assertThat(index.lookup("1002", null)).isEmpty();
    }

    @Test
    @DisplayName("번들 CSV: 이름 정규화(동작(현충원)·서울역), 4호선 운영기관 경계 — 같은 섬식이라도 사당(서울교통공사 우측)=왼쪽, 금정(코레일 좌측)=오른쪽")
    void bundledCsv() {
        SubwayDoorSideIndex index = new SubwayDoorSideIndex();

        assertThat(index.lookup("1002", "강남")).contains(Side.RIGHT);
        assertThat(index.lookup("1004", "동작")).contains(Side.RIGHT);
        assertThat(index.lookup("1004", "사당")).contains(Side.LEFT);
        assertThat(index.lookup("1004", "금정")).contains(Side.RIGHT);
        assertThat(index.lookup("1001", "서울역")).isPresent();
    }

    @Test
    @DisplayName("번들 CSV 를 운영 역 마스터(696역)에 대 보면 498역에 값이 나온다 — 재생성 때 이름·코드가 어긋나면 여기서 떨어진다")
    void coverageOnStationMaster() throws Exception {
        SubwayDoorSideIndex index = new SubwayDoorSideIndex();
        JsonNode stations;
        try (InputStream in = getClass().getResourceAsStream("/otp/subway-patterns/subway-stations.json")) {
            stations = new ObjectMapper().readTree(in).get("stations");
        }

        int filled = 0;
        int line2 = 0;
        for (JsonNode station : stations) {
            String lineId = station.get("lineId").asText();
            if (index.lookup(lineId, station.get("stationName").asText()).isPresent()) {
                filled++;
                if ("1002".equals(lineId)) {
                    line2++;
                }
            }
        }

        assertThat(filled).isGreaterThanOrEqualTo(490);
        assertThat(line2).isGreaterThanOrEqualTo(48);
    }
}
