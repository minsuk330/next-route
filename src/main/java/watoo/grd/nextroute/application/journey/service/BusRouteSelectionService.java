package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.event.JourneyVersionChangedEvent;
import watoo.grd.nextroute.application.journey.exception.StaleRouteRevisionException;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * 탄(탈) 버스 노선 선택(#598).
 *
 * <p>검색은 서는 정류장이 완전히 같은 버스들을 한 구간에 묶고(#605), 여정은 그 노선들을 후보로 저장한 뒤 첫 후보를
 * 고른 것으로 시작한다. 사용자가 실제로 탄 노선이 다르면 앱이 탑승 때 이 선택을 보낸다. 묶인 노선은 정류장이 같아
 * 구간 구조가 그대로이므로 <b>개정본을 새로 만들지 않고 선택만 바꾼다.</b>
 *
 * <p>지키는 것:
 * <ul>
 *   <li>여정 행을 잠근다 — 재탐색 수락·구간 전이와 같은 잠금이라 서로 줄을 선다.</li>
 *   <li><b>{@code commandVersion} 은 올리지 않는다.</b> 앱은 이 선택과 탑승 전이를 연달아 보낸다. 올리면 선택 전에 읽은
 *       번호로 온 전이가 충돌(409)로 거절된다. 조회 갱신은 {@code evaluationVersion} 이 알린다.</li>
 *   <li>평가를 지금으로 당긴다({@code scheduleEvaluationAt}). 돌고 있던 평가는 저장 때 소유 검사에 걸려 버려지므로
 *       옛 노선 기준 결과가 방금 비운 값을 다시 채우지 못한다.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BusRouteSelectionService {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneyQueryService queryService;
    private final RerouteRefreshSignal rerouteRefreshSignal;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    /**
     * @param rideLegIndex 대상 버스 <b>탑승</b> 구간의 순번. null 이면 지금 단계 기준(탑승 중이면 그 구간, 버스를 기다리는
     *                     중이면 바로 다음 구간)
     * @return 선택이 반영된 여정
     */
    @Transactional
    public JourneySnapshot select(Long userId, Long journeyId, Long routeOptionId, int routeRevision,
                                  Integer rideLegIndex) {
        JourneySession journey = sessionRepository.findByIdAndUserIdForUpdate(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }
        if (journey.getRouteRevision() != routeRevision) {
            throw new StaleRouteRevisionException(journey.getRouteRevision());
        }

        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(journeyId, routeRevision);
        JourneyLeg ride = rideLeg(legs, journey.getCurrentLegIndex(), rideLegIndex);
        JourneyLeg wait = legAt(legs, ride.getLegIndex() - 1);
        if (wait == null || wait.getType() != JourneyLegType.WAIT_BUS
                || (ride.getState() != JourneyLegState.PENDING && ride.getState() != JourneyLegState.ACTIVE)) {
            throw new BusinessException(JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
        }

        // 후보는 기다리는 단계가 들고 있다.
        JourneyLegRouteOption option = routeOptionRepository.findById(routeOptionId)
                .filter(found -> found.getDeletedAt() == null)
                .filter(found -> Objects.equals(found.getJourneyLegId(), wait.getId()))
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.BUS_ROUTE_OPTION_NOT_FOUND));

        if (Objects.equals(wait.getSelectedRouteOptionId(), option.getId())
                && Objects.equals(ride.getSelectedRouteOptionId(), option.getId())) {
            // 같은 후보 재전송 — 아무것도 바꾸지 않는다(버전도, 평가 시각도).
            return queryService.snapshotOf(journey);
        }

        Long previous = wait.getSelectedRouteOptionId();
        wait.reselectRouteOption(option.getId(), option.getRouteId());
        ride.reselectRouteOption(option.getId(), option.getRouteId());

        Instant now = Instant.now(clock);
        journey.scheduleEvaluationAt(now);
        // 재탐색 보드도 옛 노선 기준이다 — 다시 찾게 한다.
        rerouteRefreshSignal.requestNow(journeyId);
        log.info("[BusRouteSelection] journeyId={} rideLegIndex={} option {} -> {} route={}({})",
                journeyId, ride.getLegIndex(), previous, option.getId(), option.getRouteName(), option.getRouteId());

        // 마지막 쓰기다 — flush 뒤 영속성 컨텍스트를 비우므로 아래에서 여정을 다시 읽는다.
        sessionRepository.bumpEvaluationVersion(journeyId, routeRevision);
        eventPublisher.publishEvent(JourneyVersionChangedEvent.of(journeyId));

        JourneySession refreshed = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        return queryService.snapshotOf(refreshed);
    }

    private static JourneyLeg rideLeg(List<JourneyLeg> legs, int currentLegIndex, Integer rideLegIndex) {
        JourneyLeg target;
        if (rideLegIndex != null) {
            target = legAt(legs, rideLegIndex);
        } else {
            JourneyLeg current = legAt(legs, currentLegIndex);
            target = current != null && current.getType() == JourneyLegType.WAIT_BUS
                    ? legAt(legs, currentLegIndex + 1)
                    : current;
        }
        if (target == null || target.getType() != JourneyLegType.RIDE_BUS) {
            throw new BusinessException(JourneyErrorCode.BUS_ROUTE_SELECTION_NOT_ALLOWED);
        }
        return target;
    }

    private static JourneyLeg legAt(List<JourneyLeg> legs, int legIndex) {
        return legs.stream().filter(leg -> leg.getLegIndex() == legIndex).findFirst().orElse(null);
    }
}
