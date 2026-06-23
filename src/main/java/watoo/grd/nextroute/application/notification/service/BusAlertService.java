package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.CancelBusAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.CreateBusAlertUseCase;
import watoo.grd.nextroute.application.notification.port.in.GetBusAlertUseCase;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.service.BusDataService;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusAlertService
        implements CreateBusAlertUseCase, CancelBusAlertUseCase, GetBusAlertUseCase {

    private static final List<AlertStatus> ACTIVE =
            List.of(AlertStatus.PENDING, AlertStatus.PROCESSING);

    private final BusArrivalAlertRepository alertRepository;
    private final BusDataService busDataService;
    private final UserRepository userRepository;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public BusAlertResponse create(long userId, BusAlertRequest request) {
        User user = requireUser(userId);

        Integer ord = resolveOrd(request.getStopId(), request.getRouteId());
        String stopName = busDataService.findStopById(request.getStopId())
                .map(s -> s.getStopName())
                .orElseThrow(() -> new IllegalArgumentException("정류소를 찾을 수 없습니다: " + request.getStopId()));
        String routeName = busDataService.findRouteById(request.getRouteId())
                .map(r -> r.getRouteName())
                .orElseThrow(() -> new IllegalArgumentException("노선을 찾을 수 없습니다: " + request.getRouteId()));

        // 멱등: 동일 (user, stop, route, ord) 활성 구독이 있으면 그대로 반환.
        BusArrivalAlert existing = alertRepository
                .findFirstByUserAndStopIdAndRouteIdAndOrdAndStatusInAndDeletedAtIsNull(
                        user, request.getStopId(), request.getRouteId(), ord, ACTIVE)
                .orElse(null);
        if (existing != null) {
            return BusAlertResponse.from(existing);
        }

        BusArrivalAlert alert = BusArrivalAlert.builder()
                .user(user)
                .stopId(request.getStopId())
                .routeId(request.getRouteId())
                .ord(ord)
                .routeName(routeName)
                .stopName(stopName)
                .userEta(request.getUserEta())
                .expiresAt(LocalDateTime.now(clock).plusMinutes(properties.getTtlMinutes()))
                .build();
        try {
            return BusAlertResponse.from(alertRepository.save(alert));
        } catch (DataIntegrityViolationException e) {
            // partial unique 충돌(동시 생성) → 기존 활성 구독 재조회 반환.
            return alertRepository
                    .findFirstByUserAndStopIdAndRouteIdAndOrdAndStatusInAndDeletedAtIsNull(
                            user, request.getStopId(), request.getRouteId(), ord, ACTIVE)
                    .map(BusAlertResponse::from)
                    .orElseThrow(() -> e);
        }
    }

    @Override
    @Transactional
    public void cancel(long userId, Long alertId) {
        User user = requireUser(userId);
        BusArrivalAlert alert = alertRepository.findByIdAndUserAndDeletedAtIsNull(alertId, user)
                .orElseThrow(() -> new NoSuchElementException("알림을 찾을 수 없습니다."));
        alert.markCanceled();
    }

    @Override
    public List<BusAlertResponse> getActive(long userId) {
        User user = requireUser(userId);
        return alertRepository.findByUserAndDeletedAtIsNull(user).stream()
                .filter(a -> ACTIVE.contains(a.getStatus()))
                .map(BusAlertResponse::from)
                .toList();
    }

    /** (stopId, routeId) → 경유순번. 단일 매핑/seq 없으면 400. */
    private Integer resolveOrd(String stopId, String routeId) {
        List<BusRouteStop> mappings = busDataService.findBusRouteByStopAndRoute(stopId, routeId);
        if (mappings.size() != 1 || mappings.get(0).getSeq() == null) {
            throw new IllegalArgumentException(
                    "정류소·노선 매핑이 유효하지 않습니다: stop=" + stopId + ", route=" + routeId);
        }
        return mappings.get(0).getSeq();
    }

    private User requireUser(long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없습니다: " + userId));
    }
}
