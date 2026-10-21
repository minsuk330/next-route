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
    default SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination) {
        return getStationsAhead(stationId, direction, destination, null);
    }

    /**
     * @param trainNo 탄(탈) 열차 원문 번호(subway-express PR-S3). 주면 응답 {@code train} 과 역마다 {@code stopsHere}·
     *                {@code expressStopsAway} 를 채운다. 열차 정보는 덧붙이는 값이라 못 구해도 목록은 그대로 준다
     */
    SubwayStationsAheadResponse getStationsAhead(String stationId, String direction, String destination,
                                                 String trainNo);
}
