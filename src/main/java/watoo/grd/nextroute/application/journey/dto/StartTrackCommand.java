package watoo.grd.nextroute.application.journey.dto;

/**
 * 단독 추적 시작(#265).
 *
 * @param patternCode 힌트일 뿐이다. 낡았으면(그래프 재빌드) 서버가 노선·승하차로 다시 찾는다
 * @param alightIndex 하차 후보의 {@code index}. 같은 정류장을 두 번 지나는 패턴에서 뒤쪽을 고를 때만 의미 있는 힌트다(#546)
 * @param replace     진행 중인 여정(일반 여정 포함)을 접고 시작한다
 */
public record StartTrackCommand(String routeGtfsId, String boardStopId, String alightStopId,
                                String patternCode, Integer alightIndex, boolean replace) {

    public StartTrackCommand(String routeGtfsId, String boardStopId, String alightStopId,
                             String patternCode, boolean replace) {
        this(routeGtfsId, boardStopId, alightStopId, patternCode, null, replace);
    }
}
