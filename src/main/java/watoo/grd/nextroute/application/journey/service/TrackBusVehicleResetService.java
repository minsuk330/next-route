package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;

/**
 * "이 버스 아님"(#265). 지금 타는 버스 단계의 고정 차량을 지운다. <b>관찰을 다시 시작하지 않는다</b> — 이미 타 있어 승차
 * 정류장 출발로는 다시 고를 수 없다. 판정은 끝난 채로 둔다({@code vehicle_matched_at} 유지·갱신). 감사는 로그·지표.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TrackBusVehicleResetService {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final io.micrometer.core.instrument.MeterRegistry meterRegistry;
    private final Clock clock;

    @Transactional
    public void reset(Long userId, Long journeyId) {
        JourneySession session = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (session.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        JourneyLeg ride = legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journeyId, session.getRouteRevision()).stream()
                .filter(leg -> leg.getLegIndex() == session.getCurrentLegIndex())
                .filter(leg -> leg.getType() == JourneyLegType.RIDE_BUS)
                .findFirst()
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.NOT_RIDING_BUS));
        Instant now = Instant.now(clock);
        legRepository.resetVehicle(ride.getId(), now);
        meterRegistry.counter("bus.match", "provider", "any", "result", "user-reset").increment();
        log.info("[TrackBus] 사용자 차량 해제 journeyId={} legIndex={} hadVehicle={}",
                journeyId, ride.getLegIndex(), ride.getVehicleId() != null);
    }
}
