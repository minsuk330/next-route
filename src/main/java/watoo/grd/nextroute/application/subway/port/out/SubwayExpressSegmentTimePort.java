package watoo.grd.nextroute.application.subway.port.out;

import java.util.List;

/**
 * 급행 한 구간(정차역 도착 → 다음 정차역 도착)의 실측 소요시간(#497).
 *
 * <p>일반 구간 표본({@link SubwaySegmentTimePort})과 따로 둔다. 그쪽은 도착 API 의 "전역 출발 → 도착"이고 일반 열차만 받는다.
 * 1호선 코레일 구간 급행은 출발을 알리지 않고, 도착 API 는 일부 정차역에 그 열차 행을 내지 않아 그 표본을 만들 수 없다.
 * 그래서 위치 API 의 정차역 도착 사이 시간을 잰다 — 정차 시간이 함께 들어 있다.
 *
 * <p>급행은 드물어 하루 표본이 적으니 여러 날에 걸쳐 둔다. 사라져도 되는 값이다 — 없으면 링은 GTFS 급행 시간을 쓴다.
 */
public interface SubwayExpressSegmentTimePort {

    /** 표본 하나를 더한다. 오래된 표본은 개수 상한에서 밀려난다. */
    void record(String lineId, String direction, String fromStationId, String toStationId, long seconds);

    /** 이 구간을 최근에 지난 급행들의 소요시간(초), 최신순. */
    List<Long> recentSeconds(String lineId, String direction, String fromStationId, String toStationId);
}
