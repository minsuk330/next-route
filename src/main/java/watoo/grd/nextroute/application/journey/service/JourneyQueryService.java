package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyLegView;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionView;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.port.in.GetActiveJourneyUseCase;
import watoo.grd.nextroute.application.journey.port.in.GetJourneyUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 여정 조회. 앱이 재실행됐을 때 화면을 되살리는 경로다.
 *
 * <p>단계마다 노선 후보를 따로 조회하지 않는다. 단계를 한 번, 그 단계들의 후보를 한 번 읽고
 * 메모리에서 붙인다 — 환승이 많은 여정일수록 단계 수만큼의 쿼리가 그대로 지연이 된다.
 */
@Service
@RequiredArgsConstructor
public class JourneyQueryService implements GetActiveJourneyUseCase, GetJourneyUseCase {

    private final JourneySessionRepository sessionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<JourneySnapshot> getActive(Long userId) {
        return sessionRepository
                .findByUserIdAndStatusAndDeletedAtIsNull(userId, JourneyStatus.ACTIVE)
                .map(this::snapshotOf);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JourneySnapshot> get(Long userId, Long journeyId) {
        return sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .map(this::snapshotOf);
    }

    /**
     * 여정 하나를 최소 상태로 만든다.
     *
     * <p>여정을 막 만든 트랜잭션 안에서도 쓴다. 그래서 이 메서드는 스스로 트랜잭션을 열지 않고
     * 부르는 쪽의 트랜잭션에 얹힌다.
     */
    public JourneySnapshot snapshotOf(JourneySession session) {
        List<JourneyLeg> legs = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        session.getId(), session.getRouteRevision());

        Map<Long, List<JourneyLegRouteOption>> optionsByLeg = legs.isEmpty()
                ? Map.of()
                : routeOptionRepository
                        .findByJourneyLegIdInAndDeletedAtIsNull(legs.stream().map(JourneyLeg::getId).toList())
                        .stream()
                        .collect(Collectors.groupingBy(JourneyLegRouteOption::getJourneyLegId));

        return JourneySnapshot.of(
                session.getId(),
                session.getStatus(),
                session.getCommandVersion(),
                session.getEvaluationVersion(),
                session.getRouteRevision(),
                session.getCurrentLegIndex(),
                session.getGuidanceLeaseUntil(),
                session.getSubwayOfflineProtectionUntil(),
                clock.instant(),
                legs.stream().map(leg -> toView(leg, optionsByLeg)).toList());
    }

    private JourneyLegView toView(JourneyLeg leg,
                                  Map<Long, List<JourneyLegRouteOption>> optionsByLeg) {
        List<JourneyRouteOptionView> options = optionsByLeg
                .getOrDefault(leg.getId(), List.of())
                .stream()
                .sorted(java.util.Comparator.comparingInt(JourneyLegRouteOption::getOptionIndex))
                .map(option -> new JourneyRouteOptionView(
                        option.getId(),
                        option.getOptionIndex(),
                        option.getLaneIndex(),
                        option.getRouteId(),
                        option.getSourceRouteId(),
                        option.getRouteName(),
                        option.getDirectionKey(),
                        option.getWayCode(),
                        option.getBoardingSeq(),
                        option.getAlightingSeq()))
                .toList();

        return new JourneyLegView(
                leg.getId(),
                leg.getLegIndex(),
                leg.getType(),
                leg.getState(),
                leg.getStartStopId(),
                leg.getEndStopId(),
                leg.getStartName(),
                leg.getEndName(),
                leg.getRouteId(),
                leg.getPlannedDurationSec(),
                leg.getEvidenceQuality(),
                leg.getSelectedRouteOptionId(),
                options);
    }
}
