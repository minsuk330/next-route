package watoo.grd.nextroute.application.subway.service;

import java.util.List;

/**
 * 급행 서비스 하나가 한 방향으로 서는 역(subway-express). {@link SubwayExpressCatalog#pattern} 이 만든다.
 *
 * <p>열차별 예외(금천구청·의왕·팔당)와 방향 한정 정차(B급행 영등포 상행)는 이미 반영돼 있고, 각역 구간
 * ({@code allStops})은 완행 순서로 펼쳐져 있다. 종착 범위로 자르는 일은 {@link SubwayStopPatternResolver} 가 한다.
 *
 * @param stopIds 진행 순서 역 id
 */
public record SubwayStopPattern(String serviceId, String lineId, String direction, String catalogVersion,
                                List<String> stopIds) {

    public SubwayStopPattern {
        stopIds = List.copyOf(stopIds);
    }

    public boolean stopsAt(String stationId) {
        return stationId != null && stopIds.contains(stationId);
    }
}
