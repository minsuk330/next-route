package watoo.grd.nextroute.application.subway.dto;

import java.util.List;

/**
 * OTP 노선의 운행 패턴 하나(같은 정차 순서를 가진 trip 묶음).
 *
 * <p>순환선은 trip 하나가 여러 바퀴를 돈다 — 2호선 본선 패턴은 정차가 517개까지 있다. 그래서
 * {@code tripCount} 는 <b>같은 노선 안에서만</b> 비교할 수 있다(본선 2~5, 지선 110대).
 *
 * @param tripCount 이 패턴을 쓰는 trip 수
 * @param stops     정차 순서. 시각은 대표 trip 하나의 값이다
 */
public record RailPattern(String routeGtfsId, int tripCount, List<Stop> stops) {

    /**
     * @param arrivalSec   서비스일 자정 기준 초. 86400 을 넘을 수 있다. 없으면 null
     * @param departureSec 위와 같다
     */
    public record Stop(String name, double lat, double lon, Integer arrivalSec, Integer departureSec) {
    }
}
