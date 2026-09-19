package watoo.grd.nextroute.application.journey.dto;

import java.time.Instant;

/**
 * 재탐색을 시작할 수 있는 <b>한 자리</b>. 원점({@link RerouteOrigin})에 "거기서 언제 내리는지"와
 * 지도 좌표를 붙인 것이다(journey-reroute-nextstop PR-2).
 *
 * <p>원점만으로는 검색을 못 한다. 검색은 좌표로 하고({@code JourneyWaypointResolver} 가 leg 에
 * 좌표가 없어 존재하는 이유가 그것이다), 출발 시각이 있어야 "그 시각에 떠나는 경로"를 물을 수
 * 있다. 그 둘을 원점과 같은 그릇에 담아 <b>공급자가 origin 마다 같은 절차를 돌게</b> 한다 —
 * 계획 하차 정류장과 주행 중 중간 정류장을 다른 코드로 다루면 두 경로의 숫자가 갈린다.
 *
 * @param origin        서울 좌표계 원점. {@code RIDE_INTERIOR} 면 주행 중 중간 정류장이고
 *                      {@code TRANSFER_WAIT} 면 원래 계획된 하차(=환승) 정류장이다
 * @param stopName      화면에 나갈 정류장 이름. 못 풀면 null
 * @param x             경도. 이 자리에서 목적지까지 다시 검색할 출발 좌표
 * @param y             위도
 * @param alightAt      사용자가 이 정류장에서 <b>내리는</b> 시각. 중간 정류장은 타고 있는 버스의
 *                      실시간 도착 시각이고, 계획 하차점은 환승 준비 완료 시각({@code userReadyAt})
 *                      이다. <b>실시간이 없으면 이 자리는 아예 만들어지지 않는다</b>(사용자 결정)
 * @param plannedAlight 원래 계획된 하차점인가. 이 자리만은 언제나 목록에 있고 기준선 노릇을 한다
 */
public record RerouteOriginStop(
        RerouteOrigin origin,
        String stopName,
        double x,
        double y,
        Instant alightAt,
        boolean plannedAlight
) {

    /** 검색·승차 예측의 기준 시각. 내린 뒤 다음 차로 걸어가는 시간을 더한다. */
    public Instant departAt(int boardingBufferSec) {
        return alightAt.plusSeconds(boardingBufferSec);
    }
}
