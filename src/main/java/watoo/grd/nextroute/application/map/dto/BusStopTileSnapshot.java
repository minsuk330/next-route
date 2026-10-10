package watoo.grd.nextroute.application.map.dto;

import java.time.Instant;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 한 번 만든 전국 정류장 타일 묶음(#326). 불변 — 다시 만들면 통째로 바꿔 끼운다.
 *
 * @param version      manifest 버전(타일 버전들의 해시)
 * @param count        전체 정류장 수(같은 정류장 합친 뒤)
 * @param tiles        key → 타일
 * @param manifestJson {@code GET /v2/tiles} 본문
 */
public record BusStopTileSnapshot(String version, int count, Instant builtAt, Map<String, Tile> tiles,
                                  byte[] manifestJson) {

    private static final Pattern KEY = Pattern.compile("^\\d{2,3}_\\d{3,4}$");

    /** 타일 key 형식({@code floor(lat*10)_floor(lng*10)}, 예 {@code 374_1270}). */
    public static boolean isKey(String key) {
        return key != null && KEY.matcher(key).matches();
    }

    /** @param json {@code GET /v2/tiles/{key}} 본문 */
    public record Tile(String version, byte[] json) {
    }
}
