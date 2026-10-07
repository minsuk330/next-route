package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.bus.dto.BusArrivalCandidate;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginStop;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.route.port.out.SearchTimeBusQueryPort;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 지금 타고 있는 버스가 <b>앞으로 설 정류장</b> 중 내릴 수 있는 자리를 고른다
 * (journey-reroute-nextstop PR-2, 원점 창).
 *
 * <p>여기서 나오는 자리마다 목적지까지 경로를 다시 찾는다. 즉 이 목록의 길이가 곧 요금이고,
 * 이 목록의 정확도가 곧 "사용자가 실제로 내릴 수 있는가"다.
 *
 * <h2>실시간이 없으면 자리를 만들지 않는다</h2>
 * 시간표로 "몇 시에 내릴 수 있다"를 만들면, 지연 중인 실제 버스와 어긋난 안내가 나가고 사용자는
 * 그 말을 믿고 몸을 움직인다. 하차는 되돌릴 수 없는 행동이라 <b>시간표 폴백을 두지 않는다</b>
 * (사용자 결정 2026-09-18). 반대로 다음 차 <b>탑승</b> 추정은 틀려도 더 기다릴 뿐이라 모델까지
 * 내려간다 — 이 비대칭은 의도다.
 *
 * <h2>어느 차가 내 차인가</h2>
 * 서버는 사용자가 탄 <b>차량을 모른다</b>(버스에는 지하철 같은 열차 바인딩이 없다). 그래서 도착
 * 정보가 주는 접근 차량 한두 대 중에서 <b>지금 내 위치와 같은 구간에 있는 차</b>를 내 차로 본다.
 * 내 위치는 앱이 보내 준 {@code remainingStops} 로 안다.
 *
 * <p>고르기는 <b>한쪽으로만 느슨하다</b>: 내 구간({@code currentSeq})과 그 다음 구간까지만 받는다.
 * 내 앞의 차를 잘못 잡으면 도착이 실제보다 <b>이르게</b> 나와 자리가 버려지거나 조금 이른 시각을
 * 말할 뿐이지만, 내 뒤의 차를 잡으면 도착이 <b>늦게</b> 나와 "아직 여유 있다"는 안내가 되고
 * 사용자는 내릴 곳을 지나친다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UpcomingStopWindowResolver {

    private final BusRideLocator locator;
    private final RegionalRideLocator regionalLocator;
    private final RegionalArrivalLookup regionalArrivals;
    private final SearchTimeBusQueryPort busQueryPort;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;

    /**
     * 주행 중인 버스의 중간 하차 후보들. 계획 하차 정류장은 <b>여기 없다</b> — 그 자리는 공급자가
     * 환승 대기 원점으로 이미 들고 있고, 두 곳에서 만들면 같은 정류장이 두 번 나온다.
     *
     * @param legs        현재 개정본의 단계 전부(공급자가 이미 읽은 것을 그대로 받는다)
     * @param waitLegIndex 갈아탈 대기 단계의 순번. 그 <b>앞 단계</b>가 지금 타고 있는 주행이다
     * @param now         기준 시각
     * @return seq 오름차순. 못 열면 빈 목록 — 예외가 아니다(재탐색은 계획 하차점만으로도 돈다)
     */
    public List<RerouteOriginStop> resolveRideInterior(List<JourneyLeg> legs, int waitLegIndex,
                                                       Instant now) {
        return resolveRideInterior(legs, waitLegIndex, now,
                properties.getNextStop().getLeadTimeSec());
    }

    /**
     * 여유 시간을 지정하는 판(#264 보드). 보드는 말풍선 기준과 같은 60초를 쓰고, 사건 기반 경로는
     * 기존 {@code next-stop.lead-time-sec}(90초)를 그대로 쓴다 — 같은 키를 바꾸면 플래그가 꺼져 있어도
     * 기존 동작이 바뀐다.
     */
    public List<RerouteOriginStop> resolveRideInterior(List<JourneyLeg> legs, int waitLegIndex,
                                                       Instant now, int leadTimeSec) {
        JourneyRerouteProperties.NextStop config = properties.getNextStop();
        if (!config.isEnabled()) {
            return List.of();
        }
        Optional<JourneyLeg> found = legs.stream()
                .filter(leg -> leg.getLegIndex() == waitLegIndex - 1)
                .findFirst();
        if (found.isEmpty()) {
            return List.of();
        }
        JourneyLeg ride = found.get();
        // 버스만이다. 하차 정류장 id 가 들어갈 칸이 버스 칸이라(PR-C) 지하철에 쓰면 주소 체계가 섞인다.
        if (ride.getType() != JourneyLegType.RIDE_BUS || ride.getState() != JourneyLegState.ACTIVE) {
            return List.of();
        }
        if (ride.getRouteId() == null || ride.getStartStopId() == null || ride.getEndStopId() == null) {
            // 서울 노선이 아니다. 보드 모드면 경기·TAGO 노선 정류장 표(#267)로 연다(#264 PR-4). 사건 기반 경로는
            // 지금처럼 열지 않는다 — 플래그가 꺼져 있으면 기존 동작이 그대로여야 한다.
            return properties.getBoard().isEnabled()
                    ? scanRegional(ride, config, now, leadTimeSec)
                    : List.of();
        }
        BusRideLocator.Located located = locator.locate(ride, config.getProgressMaxAgeSec(), now);
        if (located.failed()) {
            metrics.recordOriginWindow(located.failureReason());
            return List.of();
        }
        return scan(ride, located.position(), now, config, leadTimeSec);
    }

    /**
     * 내 위치 다음 정류장부터 계획 하차 <b>직전</b>까지 훑으며 실시간 도착을 묻는다.
     *
     * <p>순번 오름차순이라 도착 시각도 오름차순이다 — 리드타임에 못 미치는 앞쪽 정류장을 지나
     * 창이 열리고, 상한에 닿으면 멈춘다. 조회 수 상한을 따로 두는 이유는 <b>버려지는 정류장도
     * 조회는 이미 나간 뒤</b>이기 때문이다.
     */
    private List<RerouteOriginStop> scan(JourneyLeg ride, BusRideLocator.Position position,
                                         Instant now,
                                         JourneyRerouteProperties.NextStop config,
                                         int leadTimeSec) {
        int currentSeq = position.currentSeq();
        int alightSeq = position.alightSeq();
        Instant earliest = now.plusSeconds(leadTimeSec);

        List<RerouteOriginStop> window = new ArrayList<>();
        String vehicleId = null;
        int lookups = 0;
        for (RouteStopProjection stop : position.stops()) {
            if (stop.getSeq() == null || stop.getSeq() <= currentSeq || stop.getSeq() >= alightSeq) {
                continue;
            }
            if (window.size() >= config.getMaxOrigins() || lookups >= config.getMaxStopLookups()) {
                break;
            }
            if (stop.getLatitude() == null || stop.getLongitude() == null) {
                // 좌표가 없으면 그 자리에서 검색을 시작할 수 없다.
                continue;
            }
            lookups++;
            SearchTimeBusQueryPort.BusQueryResult<BusArrivalInfo> result = busQueryPort
                    .getArrInfoByStop(stop.getStopId(), ride.getRouteId(), stop.getSeq(),
                            config.getArrivalBudgetMs());
            if (!result.isOk()) {
                // 막힌 문을 정류장마다 다시 두드리지 않는다.
                metrics.recordOriginWindow("arrival-blocked");
                break;
            }
            Optional<BusArrivalCandidate> mine =
                    locator.pickMyBus(result.data(), vehicleId, currentSeq, now);
            if (mine.isEmpty()) {
                metrics.recordOriginWindow("no-realtime");
                continue;
            }
            vehicleId = mine.get().vehicleId() == null ? vehicleId : mine.get().vehicleId();
            Optional<Instant> alightAt = locator.arrivalAt(mine.get());
            if (alightAt.isEmpty()) {
                metrics.recordOriginWindow("no-realtime");
                continue;
            }
            if (alightAt.get().isBefore(earliest)) {
                // 너무 가깝다. 알림을 읽고 문 앞으로 갈 시간이 없다.
                metrics.recordOriginWindow("below-lead-time");
                continue;
            }
            window.add(new RerouteOriginStop(
                    new RerouteOrigin(ride.getLegIndex(), stop.getStopId(), ride.getRouteId(),
                            stop.getSeq(), RerouteOriginKind.RIDE_INTERIOR),
                    stop.getStopName(), stop.getLongitude(), stop.getLatitude(),
                    alightAt.get(), false));
            metrics.recordOriginWindow("admitted");
        }
        return List.copyOf(window);
    }


    /**
     * 경기·TAGO 버스의 중간 정류장들(#264 PR-4). 서울 {@link #scan} 과 같은 규칙이다 — 지금 위치 다음부터 계획 하차
     * 직전까지, 승객이 내릴 수 있는 정류장(경유 가상점 제외)만, 내 차가 여유 시간 뒤에 닿는 곳만, 상한까지.
     * 할당량이 다 떨어지면 그 회차는 멈춘다(다른 기점은 계속 돈다).
     */
    private List<RerouteOriginStop> scanRegional(JourneyLeg ride, JourneyRerouteProperties.NextStop config,
                                                 Instant now, int leadTimeSec) {
        RegionalRideLocator.Located located = regionalLocator.locate(ride, config.getProgressMaxAgeSec(), now);
        if (located.failed()) {
            metrics.recordOriginWindow(located.failureReason());
            return List.of();
        }
        RegionalRideLocator.Position position = located.position();
        Instant earliest = now.plusSeconds(leadTimeSec);
        List<RerouteOriginStop> window = new ArrayList<>();
        int lookups = 0;
        for (int i = position.currentIndex() + 1; i < position.alightIndex(); i++) {
            var stop = position.stops().get(i);
            if (RegionalRideLocator.isWaypoint(stop) || stop.lat() == null || stop.lon() == null) {
                continue;
            }
            if (window.size() >= config.getMaxOrigins() || lookups >= config.getMaxStopLookups()) {
                break;
            }
            lookups++;
            int expectedAway = stop.seq() - position.stops().get(position.currentIndex()).seq();
            RegionalArrivalLookup.Lookup lookup = regionalArrivals.arrivalAt(position.provider(),
                    position.routeId(), stop.stopId(), expectedAway);
            if (lookup.failed()) {
                metrics.recordOriginWindow(lookup.failureReason());
                if (lookup.quotaExhausted() || "arrival-blocked".equals(lookup.failureReason())) {
                    break;
                }
                continue;
            }
            if (lookup.at().isBefore(earliest)) {
                metrics.recordOriginWindow("below-lead-time");
                continue;
            }
            window.add(new RerouteOriginStop(
                    new RerouteOrigin(ride.getLegIndex(), stop.stopId(), position.routeId(), stop.seq(),
                            RerouteOriginKind.RIDE_INTERIOR, null, null, position.provider().name()),
                    stop.stopName(), stop.lon(), stop.lat(), lookup.at(), false));
            metrics.recordOriginWindow("admitted");
        }
        return List.copyOf(window);
    }
}
