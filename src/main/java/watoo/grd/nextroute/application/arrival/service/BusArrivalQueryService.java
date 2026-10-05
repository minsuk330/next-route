package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    /**
     * 검색 flow와 동일한 Redis 캐시(15s TTL)를 재사용한다({@link SearchTimeBusQueryPort} → CachedSearchTimeBusAdapter).
     * 캐시 hit이면 provider 미호출 — 검색·공용 엔드포인트가 같은 캐시 엔트리를 공유해 공유 provider 호출을 줄인다.
     * 조회 실패(BLOCKED/LIMITED/ERROR)는 빈 결과로 graceful 처리(검색 flow와 동일 시맨틱).
     */
    private final SearchTimeBusQueryPort busQueryPort;
    private final BusRouteStopRepository busRouteStopRepository;
    /** 서울 순번이 없는 조합(경기 노선)은 경기도 API 로. 길찾기 응답이 경기 구간에 GBIS id 를 채운다. */
    private final GyeonggiBusArrivalPort gyeonggiBusArrivalPort;

    @Override
    public List<BusArrivalResponse> getArrivals(String stopId, String routeId) {
        // (stopId, routeId) → 경유순번(seq). Seoul getArrInfoByRoute는 ord 필수.
        List<BusRouteStop> mappings = busRouteStopRepository.findByRouteIdAndStopId(routeId, stopId);
        if (mappings.isEmpty()) {
            // 서울 카탈로그에 없는 조합 → 경기 노선으로 본다(경기 API 는 순번 없이 조회). 경기에도 없으면 빈 결과.
            return getGyeonggiArrivals(stopId, routeId);
        }
        if (mappings.size() != 1) {
            // 루프노선 다중순번(모호) → 빈 결과. 서울 노선이 확실하므로 경기로 넘기지 않는다.
            return List.of();
        }
        Integer ord = mappings.get(0).getSeq();
        if (ord == null) {
            return List.of();
        }

        BusQueryResult<BusArrivalInfo> arrRes = busQueryPort.getArrInfoByStop(stopId, routeId, ord);
        if (!arrRes.isOk()) {
            // BLOCKED/LIMITED/ERROR → 빈 결과(조회 못 함). "버스 없음"(OK+빈 리스트)과 구분되지만 응답은 동일하게 빈 리스트.
            return List.of();
        }

        return arrRes.data().stream()
                .map(info -> BusArrivalResponse.builder()
                        .routeId(info.routeId())
                        .arrivalMsg1(info.arrivalMsg1())
                        .predictTime1(info.sectionTime1())
                        .congestionNum1(info.congestionNum1())
                        .arrivalMsg2(info.arrivalMsg2())
                        .predictTime2(info.sectionTime2())
                        .congestionNum2(info.congestionNum2())
                        .build())
                .toList();
    }

    private List<BusArrivalResponse> getGyeonggiArrivals(String stationId, String routeId) {
        BusQueryResult<GyeonggiBusArrival> res = gyeonggiBusArrivalPort.getArrival(stationId, routeId);
        if (!res.isOk()) {
            // 서울 경로와 같은 시맨틱: 조회 못 함(BLOCKED/ERROR)도 응답은 빈 리스트.
            return List.of();
        }
        return res.data().stream().map(GyeonggiArrivalMapper::toResponse).toList();
    }
}
