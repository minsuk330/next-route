package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheReadPort;
import watoo.grd.nextroute.application.bus.port.out.BusRealtimeCacheWritePort;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 알림 디스패치 오케스트레이션(트랜잭션 밖). 만료 → bounded 로드 → (stop,route,ord) dedup 도착조회
 * → 임계 도달 alert 만 outbox enqueue. Toss 발송은 이 경로가 하지 않는다(relay 소관) — polling 이
 * 외부 지연서 분리됨. 발송 상태 전이는 {@link BusAlertOutboxEnqueueService}/relay 가 소유.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusAlertDispatchService {

    private final BusArrivalAlertRepository alertRepository;
    private final BusAlertStateService stateService;
    private final BusAlertOutboxEnqueueService outboxEnqueueService;
    private final BusApiPort busApiPort;
    private final BusRealtimeCacheReadPort cacheReadPort;
    private final BusRealtimeCacheWritePort cacheWritePort;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    public void dispatchDue() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            stateService.expireAndReclaim(now);

            LocalDateTime retryBefore = now.minusSeconds(properties.getBackoffSeconds());
            List<BusArrivalAlert> due = alertRepository.findDispatchable(
                    now, retryBefore, PageRequest.of(0, properties.getBatchSize()));
            if (due.isEmpty()) return;

            Map<Long, Integer> enqueuedPerUser = new HashMap<>();

            // (stopId, routeId, ord) 그룹화 → 그룹당 도착 1회 조회(dedup).
            Map<String, List<BusArrivalAlert>> groups = due.stream()
                    .collect(Collectors.groupingBy(a -> a.getStopId() + "|" + a.getRouteId() + "|" + a.getOrd()));

            for (List<BusArrivalAlert> group : groups.values()) {
                // 그룹(=정류소·노선·순번) 단위 격리: API 오류가 다른 그룹을 막지 않게.
                try {
                    processGroup(group, now, enqueuedPerUser);
                } catch (Exception e) {
                    BusArrivalAlert s = group.get(0);
                    log.warn("[BusAlertDispatch] 그룹 처리 실패 stop={} route={} ord={}: {}",
                            s.getStopId(), s.getRouteId(), s.getOrd(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("[BusAlertDispatch] 디스패치 실패: {}", e.getMessage(), e);
        }
    }

    private void processGroup(List<BusArrivalAlert> group, LocalDateTime now,
                              Map<Long, Integer> enqueuedPerUser) {
        BusArrivalAlert sample = group.get(0);
        // 그룹당 도착 1회 조회(dedup). 타겟 버스 선택은 alert.userEta 별로 다르므로 per-alert 계산.
        List<BusArrivalInfo> arrivals = loadArrivals(sample);
        if (arrivals.isEmpty()) return;

        for (BusArrivalAlert alert : group) {
            // alert 단위 격리: 한 건의 예상외 예외가 같은 그룹의 나머지를 막지 않게.
            try {
                processAlert(alert, arrivals.get(0), now, enqueuedPerUser);
            } catch (Exception e) {
                log.warn("[BusAlertDispatch] alert={} 처리 실패: {}", alert.getId(), e.getMessage(), e);
            }
        }
    }

    /**
     * 그룹(정류소·노선·순번)의 실시간 도착정보 로드.
     * <p>cacheReadEnabled(Step E): 실시간 관측 캐시 우선 조회 → hit 이면 provider 미호출(busApiCalls 절감),
     * miss 면 provider 호출 후 캐시 프라이밍(collector/검색과 공유). off 면 항상 provider 직접 호출(기존 동작).
     * <p>알림은 검색 quota/circuit-breaker 와 무관한 {@link BusApiPort} 경로를 유지한다(검색 예산 미결합).
     */
    private List<BusArrivalInfo> loadArrivals(BusArrivalAlert sample) {
        String stopId = sample.getStopId();
        String routeId = sample.getRouteId();
        int ord = sample.getOrd();
        if (properties.isCacheReadEnabled()) {
            Optional<List<BusArrivalInfo>> hit = cacheReadPort.getArrivals(stopId, routeId, ord);
            if (hit.isPresent()) return hit.get();
        }
        List<BusArrivalInfo> fresh = busApiPort.getArrInfoByStop(stopId, routeId, String.valueOf(ord));
        if (properties.isCacheReadEnabled() && !fresh.isEmpty()) {
            cacheWritePort.putArrivals(stopId, routeId, ord, fresh); // 프라이밍 — 이후 사이클/검색/collector 공유
        }
        return fresh;
    }

    private void processAlert(BusArrivalAlert alert, BusArrivalInfo arrival, LocalDateTime now,
                              Map<Long, Integer> enqueuedPerUser) {
        Integer target = selectTargetSeconds(arrival, alert.getUserEta(), now);
        if (target == null || target > properties.getThresholdSeconds()) {
            return; // userEta 이후 버스 없음 or 아직 임계 밖
        }
        long userKey = alert.getUser().getTossUserKey();
        if (enqueuedPerUser.getOrDefault(userKey, 0) >= properties.getPerUserCycleCap()) {
            return; // 다음 사이클로
        }
        int arrivalMin = (int) Math.ceil(target / 60.0);

        // enqueue 만(Toss 미호출). 발송은 relay. dedup 으로 중복 적재 흡수.
        if (outboxEnqueueService.enqueueTossArrival(alert, arrivalMin)) {
            enqueuedPerUser.merge(userKey, 1, Integer::sum);
        }
    }

    /**
     * userEta 이후(=사용자가 정류소 도착한 뒤) 가장 먼저 오는 버스의 도착예정초.
     * 도착예정 1·2번째 버스 중 (now + predict) >= userEta 인 것 중 가장 빠른 것.
     * userEta 이후 버스가 없으면 null.
     */
    private Integer selectTargetSeconds(BusArrivalInfo info, LocalDateTime userEta, LocalDateTime now) {
        long userGapSec = java.time.Duration.between(now, userEta).getSeconds();
        Integer best = null;
        for (Integer predict : new Integer[]{info.sectionTime1(), info.sectionTime2()}) {
            if (predict == null || predict < 0) continue;
            if (predict < userGapSec) continue;          // 사용자 도착 전에 떠나는 버스 → 제외
            if (best == null || predict < best) best = predict;
        }
        return best;
    }
}
