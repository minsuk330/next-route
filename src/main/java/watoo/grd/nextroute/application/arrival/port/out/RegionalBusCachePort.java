package watoo.grd.nextroute.application.arrival.port.out;

import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;

import java.util.List;
import java.util.Optional;

/**
 * 경기(GBIS)·TAGO 버스 도착 조회의 서버 공유 캐시(bus-multi-instance PR-4). 서울 버스 캐시 포트는 {@code BusArrivalInfo} 와
 * 순번(seq)에 묶여 있어 따로 둔다.
 *
 * <p>규칙: 조회 성공은 <b>빈 결과도 저장</b>한다(경기 resultCode 4 "그 정류장에 그 노선 없음"이 흔한 답이고, 안 저장하면
 * 하루 10,000 한도를 더 쓴다 — 기존 JVM 캐시와 같은 규칙). 실패(ERROR·LIMITED·BLOCKED)는 저장하지 않는다. 저장소 오류는
 * miss 로 내려앉힌다.
 */
public interface RegionalBusCachePort {

    Optional<List<GyeonggiBusArrival>> getGyeonggi(String stationId, String routeId);

    void putGyeonggi(String stationId, String routeId, List<GyeonggiBusArrival> data);

    Optional<List<TagoBusArrival>> getTago(String cityCode, String nodeId, String routeId);

    void putTago(String cityCode, String nodeId, String routeId, List<TagoBusArrival> data);
}
