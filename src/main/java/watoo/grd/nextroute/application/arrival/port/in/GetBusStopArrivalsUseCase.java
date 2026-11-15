package watoo.grd.nextroute.application.arrival.port.in;

import java.util.List;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;

/** 정류장 단위 버스 도착(#577). */
public interface GetBusStopArrivalsUseCase {

    int MAX_STOPS = 10;

    /** 정류장 하나의 노선별 도착. 모르는 id, 조회 실패 → 빈 리스트. */
    List<BusStopArrivalResponse> getStopArrivals(String stopId);

    /** 정류장 여러 곳(최대 {@value #MAX_STOPS}). 요청 순서 그대로, 중복 id 는 한 번. */
    List<StopArrivals> getStopsArrivals(List<String> stopIds);
}
