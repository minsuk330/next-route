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
import watoo.grd.nextroute.application.notification.config.TossMessengerProperties;
import watoo.grd.nextroute.application.notification.exception.TossMessengerException;
import watoo.grd.nextroute.application.notification.port.out.TossMessengerPort;
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
 * 알림 디스패치 오케스트레이션(트랜잭션 밖). 만료/reclaim → bounded 로드 → (stop,route,ord) dedup 조회
 * → 임계 도달 그룹만 claim/send/mark. 외부 토스 호출은 {@link BusAlertStateService} 트랜잭션 밖에서 수행.
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
    private final TossMessengerPort tossMessengerPort;
    private final BusArrivalAlertProperties properties;
    private final TossMessengerProperties messengerProperties;
    private final Clock clock;

    public void dispatchDue() {
        try {
            LocalDateTime now = LocalDateTime.now(clock);
            stateService.expireAndReclaim(now);

            LocalDateTime retryBefore = now.minusSeconds(properties.getBackoffSeconds());
            List<BusArrivalAlert> due = alertRepository.findDispatchable(
                    now, retryBefore, PageRequest.of(0, properties.getBatchSize()));
            if (due.isEmpty()) return;

            String templateSetCode = messengerProperties.getBusArrivalTemplateSetCode();
            Map<Long, Integer> sentPerUser = new HashMap<>();

            // (stopId, routeId, ord) 그룹화 → 그룹당 도착 1회 조회(dedup).
            Map<String, List<BusArrivalAlert>> groups = due.stream()
                    .collect(Collectors.groupingBy(a -> a.getStopId() + "|" + a.getRouteId() + "|" + a.getOrd()));

            for (List<BusArrivalAlert> group : groups.values()) {
                // 그룹(=정류소·노선·순번) 단위 격리: API 오류가 다른 그룹을 막지 않게.
                try {
                    processGroup(group, now, templateSetCode, sentPerUser);
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
                              String templateSetCode, Map<Long, Integer> sentPerUser) {
        BusArrivalAlert sample = group.get(0);
        // 그룹당 도착 1회 조회(dedup). 타겟 버스 선택은 alert.userEta 별로 다르므로 per-alert 계산.
        List<BusArrivalInfo> arrivals = loadArrivals(sample);
        if (arrivals.isEmpty()) return;

        for (BusArrivalAlert alert : group) {
            // alert 단위 격리: 한 건의 예상외 예외가 같은 그룹의 나머지를 막지 않게.
            try {
                processAlert(alert, arrivals.get(0), now, templateSetCode, sentPerUser);
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
                              String templateSetCode, Map<Long, Integer> sentPerUser) {
        Integer target = selectTargetSeconds(arrival, alert.getUserEta(), now);
        if (target == null || target > properties.getThresholdSeconds()) {
            return; // userEta 이후 버스 없음 or 아직 임계 밖
        }
        long userKey = alert.getUser().getTossUserKey();
        if (sentPerUser.getOrDefault(userKey, 0) >= properties.getPerUserCycleCap()) {
            return; // 다음 사이클로
        }
        int arrivalMin = (int) Math.ceil(target / 60.0);

        // outbox on: enqueue 만 하고 Toss 는 호출하지 않는다(발송은 relay). polling 이 외부 지연서 분리됨.
        if (properties.isOutboxEnabled()) {
            if (outboxEnqueueService.enqueueTossArrival(alert, arrivalMin)) {
                sentPerUser.merge(userKey, 1, Integer::sum);
            }
            return;
        }

        // outbox off(레거시): claim 후 인라인 발송.
        if (!stateService.claim(alert.getId())) {
            return; // 이미 PENDING 아님
        }
        sentPerUser.merge(userKey, 1, Integer::sum);
        sendOne(alert, userKey, templateSetCode, arrivalMin);
    }

    private void sendOne(BusArrivalAlert alert, long userKey, String templateSetCode, int arrivalMin) {
        try {
            tossMessengerPort.sendMessage(userKey, templateSetCode, Map.of(
                    "routeName", alert.getRouteName(),
                    "stopName", alert.getStopName(),
                    "arrivalMin", arrivalMin));
            stateService.markSent(alert.getId());
        } catch (TossMessengerException e) {
            if (e.isPermanent()) {
                log.warn("[BusAlertDispatch] alert={} permanent 실패: {}", alert.getId(), e.getMessage());
                stateService.markPermanentFailure(alert.getId(), e.getMessage());
            } else {
                log.warn("[BusAlertDispatch] alert={} transient 실패(재시도 대상): {}", alert.getId(), e.getMessage());
                stateService.markTransientFailure(alert.getId(), e.getMessage());
            }
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
