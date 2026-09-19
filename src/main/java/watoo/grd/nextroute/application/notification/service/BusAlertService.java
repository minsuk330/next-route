package watoo.grd.nextroute.application.notification.service;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.notification.config.BusArrivalAlertProperties;
import watoo.grd.nextroute.application.notification.dto.BusAlertRequest;
import watoo.grd.nextroute.application.notification.dto.BusAlertResponse;
import watoo.grd.nextroute.application.notification.port.in.BusAlertUseCase;
import watoo.grd.nextroute.domain.bus.entity.BusRouteStop;
import watoo.grd.nextroute.domain.bus.repository.BusRouteRepository;
import watoo.grd.nextroute.domain.bus.repository.BusRouteStopRepository;
import watoo.grd.nextroute.domain.bus.repository.BusStopRepository;
import watoo.grd.nextroute.domain.notification.entity.AlertStatus;
import watoo.grd.nextroute.domain.notification.entity.BusArrivalAlert;
import watoo.grd.nextroute.domain.notification.repository.BusArrivalAlertRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import watoo.grd.nextroute.application.notification.exception.TossIdentityRequiredException;

import java.util.NoSuchElementException;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BusAlertService implements BusAlertUseCase {

    private static final List<AlertStatus> ACTIVE =
            List.of(AlertStatus.PENDING, AlertStatus.PROCESSING);

    private final BusArrivalAlertRepository alertRepository;
    private final BusStopRepository busStopRepository;
    private final BusRouteRepository busRouteRepository;
    private final BusRouteStopRepository busRouteStopRepository;
    private final UserRepository userRepository;
    private final BusArrivalAlertProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public BusAlertResponse create(long userId, BusAlertRequest request) {
        User user = requireTossUser(userId);

        Integer ord = resolveOrd(request.getStopId(), request.getRouteId());
        String stopName = busStopRepository.findByStopId(request.getStopId())
                .map(s -> s.getStopName())
                .orElseThrow(() -> new IllegalArgumentException("정류소를 찾을 수 없습니다: " + request.getStopId()));
        String routeName = busRouteRepository.findByRouteId(request.getRouteId())
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
                .busArrivalMinutes(request.getBusArrivalMinutes())
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
        List<BusRouteStop> mappings = busRouteStopRepository.findByRouteIdAndStopId(routeId, stopId);
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

    /**
     * 알림 구독은 토스 신원이 있는 사용자만 만들 수 있다.
     *
     * <p>발송 채널이 토스뿐이라 없으면 받을 수단이 없다. 만드는 시점에 막지 않으면 요청은
     * 성공하고 백그라운드 발송만 조용히 실패해, 사용자는 알림이 온다고 믿게 된다.
     */
    private User requireTossUser(long userId) {
        User user = requireUser(userId);
        if (!user.hasTossIdentity()) {
            throw new TossIdentityRequiredException();
        }
        return user;
    }
}
