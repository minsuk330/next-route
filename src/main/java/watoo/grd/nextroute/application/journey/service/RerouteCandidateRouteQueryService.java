package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateRoute;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.port.in.GetRerouteCandidateRouteUseCase;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * 재탐색 후보 하나의 경로(#259).
 *
 * <p><b>읽기만 한다.</b> 제안 상태를 바꾸지 않는다 — 사용자는 여러 후보를 열어 보고 그중 하나를
 * 고른다. 후보 읽기와 유효성 판정은 수락과 같은 {@link RerouteCandidateReader} 를 쓴다. 두 경로가
 * 따로 판정하면 "보이는데 수락은 안 되는" 후보가 생긴다.
 *
 * <p>지나간 하차점 걸러내기(목록의 validate-on-read)는 여기서 하지 않는다. 목록이 그 일을 하고,
 * 수락이 {@code REROUTE_ALIGHT_UNAVAILABLE} 로 마지막에 막는다.
 */
@Service
@RequiredArgsConstructor
public class RerouteCandidateRouteQueryService implements GetRerouteCandidateRouteUseCase {

    private final JourneySessionRepository sessionRepository;
    private final RerouteProposalRepository proposalRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final RerouteCandidateReader candidateReader;
    private final BusRideLocator rideLocator;
    private final JourneyRerouteProperties properties;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public RerouteCandidateRoute get(Long userId, Long journeyId, Long proposalId,
                                     String candidateId) {
        Instant now = Instant.now(clock);
        JourneySession journey = sessionRepository.findByIdAndUserIdAndDeletedAtIsNull(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        RerouteProposal proposal = proposalRepository.findById(proposalId)
                .filter(found -> found.getJourneyId().equals(journeyId))
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_NOT_FOUND));
        RerouteCandidate candidate = candidateReader.read(proposal, candidateId);

        // 끝난 여정·처리된 제안·움직인 여정은 전부 같은 뜻이다 — 앱은 목록을 새로 받으면 된다.
        if (journey.getStatus() != JourneyStatus.ACTIVE
                || proposal.getStatus() != RerouteProposalStatus.ACTIVE
                || RerouteCandidateReader.isStale(journey, proposal, candidate, now)) {
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
        if (!(candidate.apply() instanceof RerouteCandidateApply.RemainingPathApply remaining)) {
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        return new RerouteCandidateRoute(proposalId, candidateId,
                ride(journey, remaining, candidate.display(), now), remaining.path());
    }

    /**
     * 지금 타고 있는 구간.
     *
     * <p>중간 하차 후보는 원점이 그 주행 단계를 가리킨다. 계획 하차점 후보는 대기 단계부터
     * 교체하므로 <b>그 바로 앞</b>이 지금 타는 주행이다.
     *
     * <p>그 단계가 <b>지금 진행 중인 주행</b>이 아니면 null 이다 — 아직 타지 않았거나 이미 내렸으면
     * "지금 타는 구간"이라는 줄 자체가 틀린 말이 된다.
     */
    private RerouteCandidateRoute.Ride ride(JourneySession journey,
                                            RerouteCandidateApply.RemainingPathApply apply,
                                            RerouteCandidateDisplay display, Instant now) {
        RerouteOrigin origin = apply.origin();
        boolean midRide = origin != null && origin.kind() == RerouteOriginKind.RIDE_INTERIOR;
        int rideLegIndex = midRide ? origin.legIndex() : apply.fromLegIndex() - 1;
        if (rideLegIndex != journey.getCurrentLegIndex()) {
            return null;
        }
        JourneyLeg ride = legRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                        journey.getId(), journey.getRouteRevision())
                .stream()
                .filter(leg -> leg.getLegIndex() == rideLegIndex)
                .findFirst()
                .orElse(null);
        if (ride == null || !ride.getType().isRiding() || ride.getState() != JourneyLegState.ACTIVE) {
            return null;
        }

        RerouteCandidateDisplay.AlightStop alight = display == null ? null : display.alight();
        String alightStopId = midRide ? origin.stopId() : ride.getEndStopId();
        return new RerouteCandidateRoute.Ride(
                ride.getLegIndex(),
                routeName(ride),
                ride.getStartName(),
                alightStopId,
                alight != null && alight.name() != null ? alight.name()
                        : midRide ? null : ride.getEndName(),
                alight == null ? null : alight.x(),
                alight == null ? null : alight.y(),
                midRide ? stopsUntil(ride, origin, now) : ride.getRemainingStops(),
                alight != null && alight.alightAt() != null ? alight.alightAt()
                        : midRide ? null : ride.getPredictedEndAt());
    }

    /**
     * 지금 위치에서 중간 하차 정류장까지 남은 정류장 수.
     *
     * <p>위치는 수락이 자를 자리를 정할 때와 같은 방법({@link BusRideLocator})으로 잰다. 못 재거나
     * 이미 지났으면 null — 이 화면에서 "이미 지났다"를 단정하는 것은 수락의 몫이다.
     */
    private Integer stopsUntil(JourneyLeg ride, RerouteOrigin origin, Instant now) {
        if (origin.seq() == null || ride.getRouteId() == null) {
            return null;
        }
        BusRideLocator.Located located = rideLocator.locate(
                ride, properties.getNextStop().getProgressMaxAgeSec(), now);
        if (located.failed()) {
            return null;
        }
        int stops = origin.seq() - located.position().currentSeq();
        return stops > 0 ? stops : null;
    }

    private String routeName(JourneyLeg ride) {
        return Optional.ofNullable(ride.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getRouteName)
                .filter(name -> !name.isBlank())
                .orElse(null);
    }
}
