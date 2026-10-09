package watoo.grd.nextroute.application.map.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.map.dto.BusStopTileSnapshot;
import watoo.grd.nextroute.application.route.port.out.TransitPatternPort.TransitStop;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("지도 정류장 타일 규칙")
class BusStopTilesTest {

    private static TransitStop bus(String id, double lat, double lon) {
        return new TransitStop(id, id, lat, lon, RoutingMode.BUS);
    }

    @Test
    @DisplayName("key = floor(lat*10)_floor(lng*10): 강남 374_1270, 0.1 배수 경계는 위 칸, 제주")
    void key() {
        assertThat(BusStopTiles.key(37.4958, 127.028999)).isEqualTo("374_1270");
        assertThat(BusStopTiles.key(37.5, 127.0)).isEqualTo("375_1270");
        assertThat(BusStopTiles.key(33.9403, 126.5)).isEqualTo("339_1265");
        assertThat(BusStopTileSnapshot.isKey("374_1270")).isTrue();
        assertThat(BusStopTileSnapshot.isKey("374-1270")).isFalse();
        assertThat(BusStopTileSnapshot.isKey("../x")).isFalse();
    }

    @Test
    @DisplayName("key 는 반올림한 좌표로 — 37.499996 은 응답 좌표 37.5 와 같은 375 칸")
    void keyUsesRoundedCoordinate() {
        assertThat(BusStopTiles.key(37.499996, 127.0)).startsWith("375_");
        assertThat(BusStopTiles.plain(37.499996).toPlainString()).isEqualTo("37.5");
    }

    @Test
    @DisplayName("숫자: 소수 5자리, 뒤 0 제거, 지수 표기 없음")
    void plainNumbers() {
        assertThat(BusStopTiles.plain(37.4958).toString()).isEqualTo("37.4958");
        assertThat(BusStopTiles.plain(130.0).toString()).isEqualTo("130");
        assertThat(BusStopTiles.plain(126.987654321).toString()).isEqualTo("126.98765");
    }

    @Test
    @DisplayName("합치기: 서울·경기·인천 같은 번호 → 서울 한 점, 300m 밖·지방 피드는 따로")
    void mergeSiblings() {
        List<TransitStop> merged = BusStopTiles.mergeSiblings(List.of(
                bus("1:BS_TAGO_GGB121000009", 37.495783, 127.028567),
                bus("1:BS_TAGO_ICB121000009", 37.495914, 127.028557),
                bus("1:BS_TAGO_SEB121000009", 37.4958, 127.028999),
                bus("1:BS_TAGO_SEB104000010", 37.5490, 127.0740),
                bus("1:BS_TAGO_GGB104000010", 37.5611, 127.0740),
                bus("1:BS_TAGO_ADB354000001", 36.4587, 128.8912),
                bus("1:BS_TAGO_TSB354000001", 36.4588, 128.8912)));

        assertThat(merged).extracting(TransitStop::gtfsId).containsExactlyInAnyOrder(
                "1:BS_TAGO_SEB121000009", "1:BS_TAGO_SEB104000010", "1:BS_TAGO_GGB104000010",
                "1:BS_TAGO_ADB354000001", "1:BS_TAGO_TSB354000001");
    }

    @Test
    @DisplayName("묶음은 대표 좌표의 타일에만 — 경기 stop 이 옆 칸이어도 서울 대표 칸")
    void representativeTile() {
        List<TransitStop> merged = BusStopTiles.mergeSiblings(List.of(
                bus("1:BS_TAGO_SEB121000001", 37.49999, 127.0), bus("1:BS_TAGO_GGB121000001", 37.50001, 127.0)));

        assertThat(BusStopTiles.byTile(merged)).containsOnlyKeys("374_1270");
    }

    @Test
    @DisplayName("타일 안 정렬은 gtfsId 순, 해시는 같은 바이트면 같고 바뀌면 다르다")
    void ordering() {
        SortedMap<String, List<TransitStop>> tiles = BusStopTiles.byTile(List.of(
                bus("b", 37.41, 127.01), bus("a", 37.42, 127.02), bus("c", 35.1, 129.0)));

        assertThat(tiles.firstKey()).isEqualTo("351_1290");
        assertThat(tiles.get("374_1270")).extracting(TransitStop::gtfsId).containsExactly("a", "b");
        byte[] body = "[1]".getBytes(StandardCharsets.UTF_8);
        assertThat(BusStopTiles.sha12(body)).hasSize(12).isEqualTo(BusStopTiles.sha12(body.clone()))
                .isNotEqualTo(BusStopTiles.sha12("[2]".getBytes(StandardCharsets.UTF_8)));
        SortedMap<String, String> v1 = new TreeMap<>(java.util.Map.of("374_1270", "aaa", "351_1290", "bbb"));
        SortedMap<String, String> v2 = new TreeMap<>(java.util.Map.of("374_1270", "aaa", "351_1290", "ccc"));
        assertThat(BusStopTiles.manifestVersion(v1)).isNotEqualTo(BusStopTiles.manifestVersion(v2));
    }
}
