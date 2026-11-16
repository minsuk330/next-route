package watoo.grd.nextroute.application.journey.service;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.arrival.dto.BusStopArrivalResponse;
import watoo.grd.nextroute.application.arrival.dto.StopArrivals;
import watoo.grd.nextroute.application.arrival.port.in.GetBusStopArrivalsUseCase;
import watoo.grd.nextroute.application.journey.dto.LaneReselection;
import watoo.grd.nextroute.application.route.mapping.BusRealtimeStopIds;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.RouteOptionSelectedBy;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 묶인 버스 대기 중 서버 기본 선택을 먼저 오는 노선으로 옮길지 정한다(#614 PR-2).
 *
 * <p>재료는 <b>정류장 단위 도착</b>(#577) 한 번 — 호출 한 번에 그 정류장 전 노선이 오고 Redis 에 캐시된다. 노선마다 도착을 묻지
 * 않는다: 서울 캐시 키가 노선별이라 후보 수만큼 호출되고, 버스 공급자 슬롯을 오래 잡고, 경기 일일 몫을 금방 태운다.
 *
 * <p>옮기는 조건(전부):
 * <ul>
 *   <li>지금 단계가 WAIT_BUS·ACTIVE, 선택 주인 DEFAULT, 후보 ≥ 2</li>
 *   <li>직전 저장 도착 예측이 <b>있고</b> [지금 − 120초, 지금 + 60초] 밖 — 안이면 선택한 버스가 도착 중이거나 막 떠났다(사용자가
 *       탔는데 탑승 전이가 아직 안 온 틈일 수 있다). 저장 예측이 없으면 그 틈을 가릴 수 없어 옮기지 않는다</li>
 *   <li>정류장 도착 조회가 OK, 지금 선택 노선의 줄을 찾음, 그 줄이 {@link #ARRIVING_ROW} 안 도착이 아님(정류장 도착 캐시 30초 나이만큼
 *       넉넉히)</li>
 *   <li>다른 후보 중 가장 먼저 오는 차가 지금 선택보다 {@link #HYSTERESIS} 이상 먼저 — 또는 지금 선택 줄이 <b>운행종료</b>이고 다른 후보는
 *       차가 있음. 시간이 빈 줄 중 출발대기·회차지대기(기점에서 곧 출발)는 "차 없음"이 아니다. 조회 실패·막힘도 아니다(안 옮김)</li>
 * </ul>
 * 후보 ↔ 줄 대조는 공급자 노선 id — 서울은 후보 {@code routeId}(busRouteId), 경기·TAGO 는 후보 {@code sourceRouteId}(서울 카탈로그로
 * 풀리지 않으면 routeId 가 비고 공급자 id 가 여기 있다) — 못 맞추면 노선 번호(그 정류장에서 유일할 때만). 못 맞춘 후보는 비교에서 뺀다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MergedBusLaneChooser {

    static final Duration HYSTERESIS = Duration.ofSeconds(60);
    static final Duration ARRIVING = Duration.ofSeconds(60);
    /** 정류장 도착 줄 기준 "곧 도착" — 캐시(30초)만큼 묵은 값일 수 있어 저장 예측 창(60초)보다 넉넉히. */
    static final Duration ARRIVING_ROW = Duration.ofSeconds(90);
    static final Duration JUST_LEFT = Duration.ofSeconds(120);
    static final String METRIC = "journey.lane.reselect";

    private static final Pattern NINE_DIGITS = Pattern.compile("^\\d{9}$");

    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final GetBusStopArrivalsUseCase stopArrivals;
    private final BusRealtimeStopIds realtimeStopIds;
    private final MeterRegistry registry;

    /** 지금 단계가 고를 대상인가(외부 조회 전 값싼 검사). */
    static boolean eligible(JourneyLeg wait) {
        return wait != null
                && wait.getType() == JourneyLegType.WAIT_BUS
                && wait.getState() == JourneyLegState.ACTIVE
                && wait.getSelectedRouteOptionId() != null
                && wait.getRouteOptionSelectedByOrDefault() == RouteOptionSelectedBy.DEFAULT;
    }

    public Optional<LaneReselection> choose(JourneyLeg wait, JourneyLeg ride, Instant now) {
        if (!eligible(wait)) {
            return Optional.empty();
        }
        List<JourneyLegRouteOption> options = routeOptionRepository
                .findByJourneyLegIdInAndDeletedAtIsNull(List.of(wait.getId()));
        if (options.size() < 2) {
            return Optional.empty();
        }
        if (wait.getPredictedEndAt() == null) {
            return skip("no_stored_prediction");
        }
        if (justArrivingOrLeft(wait.getPredictedEndAt(), now)) {
            return skip("bus_at_stop");
        }
        String stopId = stopIdOf(wait);
        if (stopId == null) {
            return skip("no_stop_id");
        }
        StopArrivals arrivals;
        try {
            List<StopArrivals> result = stopArrivals.getStopsArrivals(List.of(stopId));
            arrivals = result.isEmpty() ? null : result.get(0);
        } catch (RuntimeException e) {
            log.debug("[LaneReselect] 정류장 도착 조회 실패 stopId={}: {}", stopId, e.toString());
            return skip("lookup_failed");
        }
        if (arrivals == null || arrivals.status() != StopArrivals.Status.OK) {
            return skip("lookup_failed");
        }
        Optional<LaneReselection> decision = decide(wait, ride, options, arrivals.arrivals());
        decision.ifPresentOrElse(
                d -> log.info("[LaneReselect] journeyId={} legIndex={} option {} -> {}({}) wait {}s -> {}s",
                        wait.getJourneyId(), wait.getLegIndex(), d.fromOptionId(), d.toOptionId(),
                        d.toRouteName(), d.fromWaitSeconds(), d.toWaitSeconds()),
                () -> registry.counter(METRIC, "result", "kept").increment());
        decision.ifPresent(d -> registry.counter(METRIC, "result", "chosen").increment());
        return decision;
    }

    /** 순수 판단. 시험용으로 열어 둔다. */
    static Optional<LaneReselection> decide(JourneyLeg wait, JourneyLeg ride, List<JourneyLegRouteOption> options,
                                            List<BusStopArrivalResponse> rows) {
        Map<Long, BusStopArrivalResponse> rowByOption = match(options, rows);
        JourneyLegRouteOption current = options.stream()
                .filter(o -> Objects.equals(o.getId(), wait.getSelectedRouteOptionId()))
                .findFirst().orElse(null);
        if (current == null || !rowByOption.containsKey(current.getId())) {
            return Optional.empty();
        }
        BusStopArrivalResponse currentRow = rowByOption.get(current.getId());
        Integer currentWait = currentRow.predictTime1();
        if (currentWait == null && !ended(currentRow)) {
            return Optional.empty();   // 출발대기·회차지대기·정보 없음 — 차가 없다고 확정할 수 없다
        }
        if (currentWait != null && currentWait <= ARRIVING_ROW.toSeconds()) {
            return Optional.empty();
        }
        Optional<JourneyLegRouteOption> best = options.stream()
                .filter(o -> !Objects.equals(o.getId(), current.getId()))
                .filter(o -> rowByOption.containsKey(o.getId()) && rowByOption.get(o.getId()).predictTime1() != null)
                .min(Comparator.comparingInt((JourneyLegRouteOption o) -> rowByOption.get(o.getId()).predictTime1())
                        .thenComparingInt(JourneyLegRouteOption::getOptionIndex));
        if (best.isEmpty()) {
            return Optional.empty();
        }
        int bestWait = rowByOption.get(best.get().getId()).predictTime1();
        boolean earlier = currentWait == null || bestWait + HYSTERESIS.toSeconds() <= currentWait;
        if (!earlier) {
            return Optional.empty();
        }
        JourneyLegRouteOption to = best.get();
        return Optional.of(new LaneReselection(wait.getLegIndex(), ride == null ? null : ride.getLegIndex(),
                current.getId(), to.getId(), to.getRouteId(), to.getRouteName(), to.getRideSeconds(),
                currentWait, bestWait));
    }

    /** 후보 → 정류장 도착 줄. 공급자 노선 id 먼저, 없으면 그 정류장에서 유일한 노선 번호. */
    static Map<Long, BusStopArrivalResponse> match(List<JourneyLegRouteOption> options,
                                                   List<BusStopArrivalResponse> rows) {
        Map<String, BusStopArrivalResponse> byId = new HashMap<>();
        Map<String, BusStopArrivalResponse> byName = new HashMap<>();
        Map<String, Integer> nameCount = new HashMap<>();
        for (BusStopArrivalResponse row : rows == null ? List.<BusStopArrivalResponse>of() : rows) {
            if (row.routeId() != null) {
                byId.putIfAbsent(row.routeId(), row);
            }
            String name = normalize(row.routeName());
            if (name != null) {
                byName.putIfAbsent(name, row);
                nameCount.merge(name, 1, Integer::sum);
            }
        }
        Map<Long, BusStopArrivalResponse> out = new HashMap<>();
        for (JourneyLegRouteOption option : options) {
            BusStopArrivalResponse row = option.getRouteId() == null ? null : byId.get(option.getRouteId());
            if (row == null && option.getSourceRouteId() != null) {
                row = byId.get(option.getSourceRouteId());
            }
            if (row == null) {
                String name = normalize(option.getRouteName());
                if (name != null && nameCount.getOrDefault(name, 0) == 1) {
                    row = byName.get(name);
                }
            }
            if (row != null) {
                out.put(option.getId(), row);
            }
        }
        return out;
    }

    /** 운행종료로 확정된 줄(서울·경기 문구 공통). */
    static boolean ended(BusStopArrivalResponse row) {
        return row.arrivalMsg1() != null && row.arrivalMsg1().contains("운행종료");
    }

    static boolean justArrivingOrLeft(Instant storedArrival, Instant now) {
        return storedArrival != null
                && !storedArrival.isBefore(now.minus(JUST_LEFT))
                && !storedArrival.isAfter(now.plus(ARRIVING));
    }

    private String stopIdOf(JourneyLeg wait) {
        Optional<String> fromSource = wait.getSourceStartStopId() == null
                ? Optional.empty() : realtimeStopIds.of(wait.getSourceStartStopId());
        if (fromSource.isPresent()) {
            return fromSource.get();
        }
        String start = wait.getStartStopId();
        return start != null && NINE_DIGITS.matcher(start).matches() ? start : null;
    }

    private static String normalize(String name) {
        if (name == null) {
            return null;
        }
        String n = name.replaceAll("\\s+", "");
        return n.isEmpty() ? null : n;
    }

    private Optional<LaneReselection> skip(String reason) {
        registry.counter(METRIC, "result", reason).increment();
        return Optional.empty();
    }
}
