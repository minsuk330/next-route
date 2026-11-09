package watoo.grd.nextroute.application.arrival.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.arrival.dto.BusArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.GyeonggiBusArrival;
import watoo.grd.nextroute.application.arrival.dto.TagoBusArrival;
import watoo.grd.nextroute.application.arrival.port.in.GetBusArrivalUseCase;
import watoo.grd.nextroute.application.arrival.port.out.GyeonggiBusArrivalPort;
import watoo.grd.nextroute.application.arrival.port.out.TagoBusArrivalPort;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.route.mapping.TagoCityCodeLookup;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort.BusQueryResult;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 버스 도착 조회. 클래스 단위 트랜잭션을 두지 않는다 — DB 는 순번 조회 한 번뿐이고(리포지토리가 제 트랜잭션으로 읽는다)
 * 그 뒤는 공급자 HTTP 호출이다. 트랜잭션을 걸어 두면 외부 호출이 끝날 때까지 커넥션을 쥐고, 띠 이름 조회
 * ({@link BusApproachStopNames}, 트랜잭션 밖에서 읽는다)가 요청마다 커넥션을 하나 더 잡아 풀이 마른다.
 */
@Service
@RequiredArgsConstructor
public class BusArrivalQueryService implements GetBusArrivalUseCase {

    /**
     * TAGO id 모양: 영문 3글자 접두사 + 숫자(구미 노선은 하이픈, 부산 일부 노선은 영문 포함 — {@code BSB5201002F00}).
     * 서울·경기 id 는 숫자뿐이라 겹치지 않는다.
     */
    private static final Pattern TAGO_ID = Pattern.compile("^[A-Z]{3}[\\dA-Z-]+$");

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
    /** TAGO 도시코드는 노선으로 찾는다(#267). 접두사 하나가 여러 시군에 걸친다. */
    private final TagoCityCodeLookup tagoCityCodeLookup;
    /** 대기 화면의 정거장 띠에 쓸 이름(bus-boarding-flow S1). 못 구하면 null — 도착 정보는 그대로 나간다. */
    private final BusApproachStopNames approachStopNames;

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

        return arrRes.data().stream().map(info -> toSeoulResponse(info, routeId, ord)).toList();
    }

    private BusArrivalResponse toSeoulResponse(BusArrivalInfo info, String routeId, int ord) {
        String vehicle1 = vehicleId(info.vehicleId1());
        String vehicle2 = vehicleId(info.vehicleId2());
        Integer stopsAway1 = seoulStopsAway(ord, info.sectionOrder1(), vehicle1);
        return BusArrivalResponse.builder()
                .routeId(info.routeId())
                .arrivalMsg1(info.arrivalMsg1())
                .predictTime1(info.sectionTime1())
                .congestionNum1(info.congestionNum1())
                .arrivalMsg2(info.arrivalMsg2())
                .predictTime2(info.sectionTime2())
                .congestionNum2(info.congestionNum2())
                .stopsAway1(stopsAway1)
                .stopsAway2(seoulStopsAway(ord, info.sectionOrder2(), vehicle2))
                .vehicleId1(vehicle1)
                .vehicleId2(vehicle2)
                .approachStops1(approachStopNames.seoul(routeId, ord, stopsAway1, info.stationName1()))
                .build();
    }

    /** 서울은 버스가 없으면 차량 id 를 {@code "0"} 으로 준다({@code BusRouteWatcher} 와 같은 규칙). */
    private static String vehicleId(String raw) {
        return raw == null || raw.isBlank() || "0".equals(raw) ? null : raw;
    }

    /**
     * 요청 정류장 순번 − 버스의 현재 구간 순번 = 도착 문구의 {@code [N번째 전]}(2026-11-10 실응답 9건으로 확인. 곧 도착이면 0).
     * 버스가 없으면(차량 id 없음, 구간 순번 0) 모른다. 음수(정류장을 지나친 값)도 모른다.
     */
    private static Integer seoulStopsAway(int ord, Integer sectionOrder, String vehicleId) {
        if (vehicleId == null || sectionOrder == null || sectionOrder <= 0) {
            return null;
        }
        int away = ord - sectionOrder;
        return away < 0 ? null : away;
    }

    private List<BusArrivalResponse> getGyeonggiArrivals(String stationId, String routeId) {
        BusQueryResult<GyeonggiBusArrival> res = gyeonggiBusArrivalPort.getArrival(stationId, routeId);
        if (!res.isOk()) {
            // 서울 경로와 같은 시맨틱: 조회 못 함(BLOCKED/ERROR)도 응답은 빈 리스트.
            return List.of();
        }
        return res.data().stream()
                .map(arrival -> {
                    // 걸러진 값(버스가 없으면 null)으로 띠를 만든다 — 원천 칸을 그대로 쓰면 버스 없는 응답에 띠만 남는다.
                    BusArrivalResponse response = GyeonggiArrivalMapper.toResponse(arrival);
                    return response.toBuilder()
                            .approachStops1(approachStopNames.gyeonggi(routeId, stationId,
                                    response.getStopsAway1(), arrival.stationName1()))
                            .build();
                })
                .toList();
    }

    private static boolean isTagoId(String id) {
        return id != null && TAGO_ID.matcher(id).matches();
    }

    private List<BusArrivalResponse> getTagoArrivals(String nodeId, String routeId) {
        // 정류장·노선이 같은 지역(접두사)이고 노선의 도시코드를 알 때만. 아니면 호출하지 않는다(틀린 도시코드 = 빈 결과뿐).
        if (!isTagoId(nodeId) || !isTagoId(routeId) || !nodeId.regionMatches(0, routeId, 0, 3)) {
            return List.of();
        }
        Optional<String> cityCode = tagoCityCodeLookup.cityCodeOfRoute(routeId);
        if (cityCode.isEmpty()) {
            return List.of();
        }
        BusQueryResult<TagoBusArrival> res = tagoBusArrivalPort.getArrivals(cityCode.get(), nodeId, routeId);
        if (!res.isOk()) {
            return List.of();
        }
        // TAGO 는 띠 이름을 주지 않는다 — 버스의 현재 정류장 이름이 없어 순번이 맞는지 확인할 수 없다(몇 정류장 전만 준다).
        return TagoArrivalMapper.toResponse(routeId, res.data()).map(List::of).orElse(List.of());
    }
}
