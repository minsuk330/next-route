package watoo.grd.nextroute.application.arrival.port.in;

import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse;

public interface GetSubwayStationArrivalsUseCase {

    /**
     * 서울 실시간 역 ID 로 곧 오는 열차를 방향별로 묻는다.
     *
     * @throws watoo.grd.nextroute.global.error.BusinessException 없는 역이면
     *         {@code SUBWAY_STATION_NOT_FOUND}
     */
    SubwayStationArrivalsResponse getArrivals(String stationId);
}
