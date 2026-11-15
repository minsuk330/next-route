package watoo.grd.nextroute.infrastructure.adapter.in.api.arrival;

import java.util.List;

final class BusStopArrivalQueryHelper {

    private BusStopArrivalQueryHelper() {
    }

    /** 공백과 빈 값을 빼고 중복을 지운 개수(서비스의 정규화와 같은 규칙). */
    static long distinctCount(List<String> ids) {
        return ids.stream().filter(id -> id != null && !id.isBlank()).map(String::trim).distinct().count();
    }
}
