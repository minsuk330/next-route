package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodes;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    /** TAGO id 모양: 영문 3글자 접두사 + 숫자(구미 노선은 하이픈 포함). 서울·경기 id 는 숫자뿐이라 겹치지 않는다. */
    private static final Pattern TAGO_ID = Pattern.compile("^[A-Z]{3}[\\d-]+$");

    /**
     * 검색 flow와 동일한 Redis 캐시(15s TTL)를 재사용한다({@link SearchTimeBusQueryPort} → CachedSearchTimeBusAdapter).
     * 캐시 hit이면 provider 미호출 — 검색·공용 엔드포인트가 같은 캐시 엔트리를 공유해 공유 provider 호출을 줄인다.
     * 조회 실패(BLOCKED/LIMITED/ERROR)는 빈 결과로 graceful 처리(검색 flow와 동일 시맨틱).
     */
    private final SearchTimeBusQueryPort busQueryPort;
    private final BusRouteStopRepository busRouteStopRepository;
    /** 서울 순번이 없는 조합(경기 노선)은 경기도 API 로. 길찾기 응답이 경기 구간에 GBIS id 를 채운다. */
    private final GyeonggiBusArrivalPort gyeonggiBusArrivalPort;
    /** 서울·경기 밖(접두사 포함 id, 예 BSB167850202)은 TAGO 로. 서울 DB 를 보지 않는다. */
    private final TagoBusArrivalPort tagoBusArrivalPort;

    @Override
    public List<BusArrivalResponse> getArrivals(String stopId, String routeId) {
        if (isTagoId(stopId) || isTagoId(routeId)) {
            return getTagoArrivals(stopId, routeId);
        }
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

    private static boolean isTagoId(String id) {
        return id != null && TAGO_ID.matcher(id).matches();
    }

    private List<BusArrivalResponse> getTagoArrivals(String nodeId, String routeId) {
        // 정류장·노선이 같은 지역(접두사)이고 도시코드 표에 있을 때만. 아니면 호출하지 않는다(틀린 도시코드 = 빈 결과뿐).
        if (!isTagoId(nodeId) || !isTagoId(routeId) || !nodeId.regionMatches(0, routeId, 0, 3)) {
            return List.of();
        }
        Optional<String> cityCode = TagoCityCodes.cityCodeOfId(nodeId);
        if (cityCode.isEmpty()) {
            return List.of();
        }
        BusQueryResult<TagoBusArrival> res = tagoBusArrivalPort.getArrivals(cityCode.get(), nodeId, routeId);
        if (!res.isOk()) {
            return List.of();
        }
        return TagoArrivalMapper.toResponse(routeId, res.data()).map(List::of).orElse(List.of());
    }
}
