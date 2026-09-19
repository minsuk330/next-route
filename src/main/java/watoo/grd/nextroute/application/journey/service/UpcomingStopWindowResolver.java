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
import watoo.grd.nextroute.application.route.service.BusTimeParser;
import watoo.grd.nextroute.common.config.ClockConfig;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.RouteStopProjection;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
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

    private final BusRouteStopRepository busRouteStopRepository;
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
            return List.of();
        }
        RidePosition position = position(ride, config, now);
        if (position == null) {
            return List.of();
        }
        return scan(ride, position, now, config);
    }

    /**
     * 지금 몇 번째 정류장을 지났는지.
     *
     * <p>노선의 정류장 목록에서 승·하차 순번을 찾고, 앱이 보내 준 <b>남은 정류장 수</b>를 하차
     * 순번에서 뺀다. 세 값 중 하나라도 애매하면 null 이다 — 순환노선처럼 같은 정류장이 한 노선에
     * 두 번 나오면 어느 쪽인지 단정할 수 없고, 잘못 단정하면 지나간 정류장을 앞으로 올 정류장으로
     * 착각한다({@code RerouteOriginResolver} 의 "가짜값 금지"와 같은 규칙).
     */
    private RidePosition position(JourneyLeg ride, JourneyRerouteProperties.NextStop config,
                                  Instant now) {
        Integer remainingStops = ride.getRemainingStops();
        Instant observedAt = ride.getProgressObservedAt();
        if (remainingStops == null || remainingStops < 0 || observedAt == null) {
            metrics.recordOriginWindow("no-progress");
            return null;
        }
        if (Duration.between(observedAt, now).getSeconds() > config.getProgressMaxAgeSec()) {
            // 낡은 관측으로 창을 열면 이미 지나간 정류장을 제안한다. 모르는 편이 낫다.
            metrics.recordOriginWindow("stale-progress");
            return null;
        }

        List<RouteStopProjection> stops = busRouteStopRepository.findStopsByRouteId(ride.getRouteId());
        Integer boardSeq = uniqueSeq(stops, ride.getStartStopId(), null);
        if (boardSeq == null) {
            metrics.recordOriginWindow("ambiguous-stop");
            return null;
        }
        Integer alightSeq = uniqueSeq(stops, ride.getEndStopId(), boardSeq);
        if (alightSeq == null) {
            metrics.recordOriginWindow("ambiguous-stop");
            return null;
        }
        int current = alightSeq - remainingStops;
        if (current < boardSeq || current >= alightSeq) {
            // 남은 정류장 수가 이 주행 구간과 맞지 않는다. 억지로 위치를 만들지 않는다.
            metrics.recordOriginWindow("position-out-of-range");
            return null;
        }
        return new RidePosition(stops, current, alightSeq);
    }

    /**
     * 내 위치 다음 정류장부터 계획 하차 <b>직전</b>까지 훑으며 실시간 도착을 묻는다.
     *
     * <p>순번 오름차순이라 도착 시각도 오름차순이다 — 리드타임에 못 미치는 앞쪽 정류장을 지나
     * 창이 열리고, 상한에 닿으면 멈춘다. 조회 수 상한을 따로 두는 이유는 <b>버려지는 정류장도
     * 조회는 이미 나간 뒤</b>이기 때문이다.
     */
    private List<RerouteOriginStop> scan(JourneyLeg ride, RidePosition position, Instant now,
                                         JourneyRerouteProperties.NextStop config) {
        int currentSeq = position.currentSeq();
        int alightSeq = position.alightSeq();
        Instant earliest = now.plusSeconds(config.getLeadTimeSec());

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
            Optional<BusArrivalCandidate> mine = pickMyBus(result.data(), vehicleId, currentSeq, now);
            if (mine.isEmpty()) {
                metrics.recordOriginWindow("no-realtime");
                continue;
            }
            vehicleId = mine.get().vehicleId() == null ? vehicleId : mine.get().vehicleId();
            Optional<Instant> alightAt = arrivalAt(mine.get());
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
     * 접근 중인 차량 중 <b>내 차</b>.
     *
     * <p>한 번이라도 차량을 잡았으면 그 뒤 정류장부터는 같은 차량만 받는다 — 구간 순번은 내가
     * 지나가는 동안 밀리지만 차량 번호는 안 밀린다. 아직 못 잡았으면 구간 순번으로 고르되,
     * <b>내 뒤의 차는 절대 받지 않는다</b>(클래스 주석의 비대칭).
     */
    private Optional<BusArrivalCandidate> pickMyBus(List<BusArrivalInfo> arrivals, String vehicleId,
                                                    int currentSeq, Instant now) {
        List<BusArrivalCandidate> candidates = arrivals.stream()
                .flatMap(info -> BusArrivalCandidate.from(info,
                        LocalDateTime.ofInstant(now, ClockConfig.KST)).stream())
                .toList();
        if (vehicleId != null) {
            return candidates.stream()
                    .filter(candidate -> vehicleId.equals(candidate.vehicleId()))
                    .findFirst();
        }
        return candidates.stream()
                .filter(candidate -> candidate.sectionOrder() != null)
                .filter(candidate -> candidate.sectionOrder() == currentSeq
                        || candidate.sectionOrder() == currentSeq + 1)
                // 같은 구간이 우선이다. 다음 구간은 "방금 정류장을 지났다"를 받아 주는 여유일 뿐이다.
                .min((left, right) -> Integer.compare(left.sectionOrder(), right.sectionOrder()));
    }

    /** 관측 시각 + 남은 소요. 관측 시각을 못 읽으면 도착 시각을 만들지 않는다. */
    private Optional<Instant> arrivalAt(BusArrivalCandidate candidate) {
        if (candidate.sectionTime() == null) {
            return Optional.empty();
        }
        return BusTimeParser.parse(candidate.dataTimestamp())
                .map(base -> base.plusSeconds(candidate.sectionTime()));
    }

    /**
     * 그 노선에서 이 정류장의 순번. 없거나 두 번 이상 나오면 null.
     *
     * @param afterSeq 이 순번보다 뒤만 본다(순환노선의 재방문을 승차 순번 기준으로 가른다). null 이면 전체
     */
    private Integer uniqueSeq(List<RouteStopProjection> stops, String stopId, Integer afterSeq) {
        List<RouteStopProjection> matches = stops.stream()
                .filter(stop -> stopId.equals(stop.getStopId()) && stop.getSeq() != null)
                .filter(stop -> afterSeq == null || stop.getSeq() > afterSeq)
                .toList();
        return matches.size() == 1 ? matches.get(0).getSeq() : null;
    }

    /**
     * 주행 중인 버스가 지금 어디인지. 정류장 목록을 한 번만 읽기 위해 함께 들고 다닌다.
     *
     * @param currentSeq 방금 지난 정류장의 순번
     * @param alightSeq  계획된 하차 정류장의 순번. 창은 그 <b>앞</b>까지다
     */
    private record RidePosition(List<RouteStopProjection> stops, int currentSeq, int alightSeq) {
    }
}
