package watoo.grd.nextroute.application.route.dto;

/**
 * ODsay mapObj의 단일 fragment: {@code routeId:laneClass:startIdx:endIdx}.
 *
 * 주의: {@code startIdx}/{@code endIdx} 명칭은 ODsay 문서 표기를 따랐지만,
 * 지하철 검색 응답에서는 stationId 성격의 값으로 관측된다.
 * <strong>전체 polyline의 point index로 사용하지 말 것.</strong>
 * 실제 slice 경계는 subPath의 startX/Y, endX/Y 좌표로 찾는다.
 */
public record OdsayMapObjFragment(
        String odsayRouteId,
        int laneClass,
        int startIdx,
        int endIdx
) {
    public boolean isWholeRoute() {
        return startIdx == -1 && endIdx == -1;
    }

    public boolean isSubway() {
        return laneClass == 2;
    }
}
