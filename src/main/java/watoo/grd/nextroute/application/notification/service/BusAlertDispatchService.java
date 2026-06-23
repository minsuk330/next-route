package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.bus.dto.BusArrivalInfo;
import watoo.grd.nextroute.application.bus.port.out.BusApiPort;
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
    private final BusApiPort busApiPort;
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
                BusArrivalAlert sample = group.get(0);
                Integer predict = firstPredictSeconds(sample);
                if (predict == null || predict < 0 || predict > properties.getThresholdSeconds()) {
                    continue;
                }
                int arrivalMin = (int) Math.ceil(predict / 60.0);

                for (BusArrivalAlert alert : group) {
                    long userKey = alert.getUser().getTossUserKey();
                    if (sentPerUser.getOrDefault(userKey, 0) >= properties.getPerUserCycleCap()) {
                        continue; // 다음 사이클로
                    }
                    if (!stateService.claim(alert.getId())) {
                        continue; // 이미 PENDING 아님
                    }
                    sentPerUser.merge(userKey, 1, Integer::sum);
                    sendOne(alert, userKey, templateSetCode, arrivalMin);
                }
            }
        } catch (Exception e) {
            log.error("[BusAlertDispatch] 디스패치 실패: {}", e.getMessage(), e);
        }
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

    private Integer firstPredictSeconds(BusArrivalAlert alert) {
        List<BusArrivalInfo> arrivals = busApiPort.getArrInfoByStop(
                alert.getStopId(), alert.getRouteId(), String.valueOf(alert.getOrd()));
        if (arrivals.isEmpty()) return null;
        return arrivals.get(0).kalPredictTime1();
    }
}
