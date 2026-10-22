package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * @param transfers 지하철 구간(#510): 이 역에서 갈아탈 수 있는 다른 노선 — 경로 미리보기 지도용. 환승 없는 역은 빈 목록.
 *                  버스 구간·노선을 모르는 지하철 구간은 null(응답에서 빠짐)
 */
public record StationResult(
        int index,
        String stationID,
        String stationName,
        Double x,
        Double y,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Transfer> transfers
) {

    public StationResult {
        transfers = transfers == null ? null : List.copyOf(transfers);
    }

    public StationResult(int index, String stationID, String stationName, Double x, Double y) {
        this(index, stationID, stationName, x, y, null);
    }

    /**
     * 갈아탈 수 있는 노선 하나. 남은 역 응답의 {@code stations[].transfers} 와 같은 모양이다.
     *
     * @param lineId   실시간 체계 노선 id({@code 1002}, {@code 1077}). 앱 노선 색 표의 키
     * @param lineName 노선 표시 이름({@code 2호선}, {@code 신분당선})
     */
    public record Transfer(String lineId, String lineName) {
    }
}
