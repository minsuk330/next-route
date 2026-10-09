package watoo.grd.nextroute.application.subway.port.out;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * 도착 API 로만 알아낸 "이 역을 떠난 열차"(#317-4). 위치 API 가 없는 노선(수인분당·경의중앙 등)용이다.
 *
 * <p>위치 출발 사건({@link SubwayTrainLogPort#findDepartures})과 <b>섞지 않는다</b> — 그쪽은 M7 자동 탑승 판정의 후보
 * 원천이라, 품질이 다른 사건이 끼면 판정이 흔들린다. 두 원천을 합치는 곳은
 * {@link watoo.grd.nextroute.application.subway.service.SubwayDepartureLookup} 하나다.
 *
 * <p>사라져도 되는 값이다. 없으면 떠난 열차 목록이 빌 뿐이다.
 */
public interface SubwayArrivalDepartureLogPort {

    /** 같은 열차가 이미 있으면 바꿔 쓴다. */
    void append(LocalDate serviceDate, String lineId, String direction, String stationId, Row row);

    List<Row> find(LocalDate serviceDate, String lineId, String direction, String stationId, Instant from, Instant to);

    /** @param departedAt 도착 API 가 이 역 출발(2)을 처음 보였거나, 도착·진입 뒤 행이 사라진 시각의 추정 */
    record Row(String trainNo, String destinationName, String trainType, Instant departedAt) {
    }
}
