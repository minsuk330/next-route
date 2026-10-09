package watoo.grd.nextroute.application.arrival.port.in;

import watoo.grd.nextroute.application.arrival.dto.SubwayStationArrivalsResponse.LineStrip;

import java.util.Optional;

/**
 * 역 도착 응답의 방향별 노선도(#317): 이 방향 열차가 오는 쪽 역과 다음 역, GTFS 계획 초.
 *
 * <p><b>응답 지연에 OTP 적재를 넣지 않는다.</b> 이미 적재된 패턴만 쓰고, 없으면 빈 값으로 답한 뒤 뒤에서 적재한다.
 */
public interface GetSubwayLineStripUseCase {

    /** @return 모르면(패턴 캐시 없음·노선에 없는 역·방향 표기 모름) empty. 예외를 던지지 않는다 */
    Optional<LineStrip> strip(String lineId, String direction, String stationId);
}
