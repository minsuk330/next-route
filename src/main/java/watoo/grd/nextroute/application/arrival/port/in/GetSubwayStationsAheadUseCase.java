package watoo.grd.nextroute.application.arrival.port.in;

import watoo.grd.nextroute.application.arrival.dto.SubwayStationsAheadResponse;

public interface GetSubwayStationsAheadUseCase {

    /**
     * @param stationId   서울 실시간 역 ID(승차역)
     * @param direction   실시간 방향 값(상행·하행·내선·외선)
     * @param destination 탄 열차 행선지. 없으면 null
     * @throws watoo.grd.nextroute.global.error.BusinessException 없는 역 404, 방향 400, 운행 정보에 없는 역 422,
     *         노선 정보 못 가져옴 503
     */
    SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination);
}
