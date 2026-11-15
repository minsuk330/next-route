package watoo.grd.nextroute.application.arrival.port.out;

import java.util.List;
import java.util.Optional;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;

/**
 * 정류장 단위 도착 캐시(공급자 하나, 정류장 하나). 성공한 빈 결과도 저장한다(밤, 버스 없음 — 안 그러면 폴링마다 원본
 * 호출). 실패는 저장하지 않는다. 캐시 장애는 miss 로 본다.
 */
public interface BusStopArrivalCachePort {

    Optional<List<BusStopArrivalResponse>> get(String source, String providerStopKey);

    void put(String source, String providerStopKey, List<BusStopArrivalResponse> rows);
}
