package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.BoardingEstimate;
import watoo.grd.nextroute.application.journey.dto.BoardingQuery;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.BoardingTimeEstimator;
import watoo.grd.nextroute.domain.journey.entity.JourneyEvent;
import watoo.grd.nextroute.domain.journey.entity.JourneyEventResult;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneyEventRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 수락을 실제로 반영하는 트랜잭션. 개정본·단계·노선 후보·환승·세션이 여기서 함께 움직인다.
 *
 * <h2>잠금과 순서</h2>
 * <b>여정 세션 행을 잠근다.</b> 단계 전이({@code JourneyCommandService})가 잠그는 것이 그 행이라,
 * 다른 것(예: 사용자 행)을 잠그면 두 흐름이 서로를 기다리지 않는다 — 아직 커밋되지 않은 탑승
 * 명령을 못 본 채 명령 번호 검사를 통과한다. 순서도 <b>세션 → 제안</b>으로 맞춘다. 뒤집으면 기존
 * 흐름과 반대라 교착이 난다.
 *
 * <h2>거절은 롤백이다</h2>
 * 제안을 {@code ACCEPTED} 로 바꾼 뒤 그냥 409 를 돌려주면 그 변경이 <b>커밋된다</b> — 제안만
 * 소모되고 여정은 그대로인, 사용자가 다시 누를 수도 없는 상태가 남는다. 그래서 재확인 실패는
 * 예외로 던져 트랜잭션을 통째로 되돌린다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteAcceptTxService {

    /** 앱이 만든 {@code eventId} 가 없으므로 서버가 결정론으로 만든다. unique 가 곧 멱등 장치다. */
    private static final String EVENT_ID_PREFIX = "reroute-accept:";

    private final JourneySessionRepository sessionRepository;
    private final JourneyRevisionRepository revisionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final TransferConnectionRepository transferRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final RerouteProposalRepository proposalRepository;
    private final JourneyEventRepository eventRepository;
    private final SubwayBindingLifecycleService subwayBindingLifecycle;
    private final JourneyRevisionComposer composer;
    private final JourneyQueryService queryService;
    private final RideTruncationResolver truncationResolver;
    private final List<BoardingTimeEstimator> boardingEstimators;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @return 새 개정본이 반영된 여정. 이미 <b>같은 후보로</b> 수락된 제안이면 지금 상태를 그대로 돌려준다
     * @throws BusinessException 만료·상태·명령 번호·개정본·지나간 단계·스냅샷 불일치
     */
    @Transactional
    public JourneySnapshot accept(Long userId, Long journeyId, Long proposalId,
                                  String candidateId) {
        Instant now = Instant.now(clock);
        JourneySession journey = sessionRepository.findByIdAndUserIdForUpdate(journeyId, userId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }

        RerouteProposal proposal = proposalRepository.findById(proposalId)
                .filter(found -> found.getJourneyId().equals(journeyId))
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_NOT_FOUND));
        RerouteCandidate candidate = candidateOf(proposal, candidateId);

        int changed = proposalRepository.acceptIfActive(
                proposalId, candidateId, now, LocalDateTime.now(clock));
        if (changed == 0) {
            // 진 쪽이다. 같은 후보의 재전송이면 이미 반영된 결과를 그대로 돌려준다.
            return alreadyAccepted(journey, proposalId, candidateId);
        }

        requireStillApplicable(journey, proposal, candidate, now);
        List<JourneyLeg> legs = currentLegs(journey);
        RideTruncationResolver.Resolved truncation = resolveTruncation(candidate.apply(), legs, now);

        JourneyRevisionComposer.Result composed = composer.compose(
                snapshotOf(journey), legs, candidate.apply(),
                truncation == null ? null : truncation.toTruncation());
        if (composed.failed()) {
            metrics.recordCandidateFiltered(composed.failureReason());
            if (truncation != null) {
                metrics.recordMidRideAccept(composed.failureReason());
            }
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
        if (truncation != null) {
            requireStillBoardable(composed.composed(), truncation, now);
        }

        applyRevision(journey, composed.composed(), candidate.apply(), truncation, now);
        recordEvent(journey, proposalId, candidateId, now);
        metrics.recordResolved("accepted", 1);
        if (truncation != null) {
            metrics.recordMidRideAccept("applied");
        }
        return queryService.snapshotOf(journey);
    }

    // ── 중간 하차 재확인 ─────────────────────────────────────────────────────

    /**
     * 주행 중인 버스를 중간에서 자르는 후보인가. 맞다면 <b>지금 다시 재어</b> 자를 자리를 정한다.
     *
     * <p>제안이 만들어진 값을 그대로 쓰지 않는 이유는 그 사이에도 <b>버스가 달리기 때문</b>이다.
     * 자를 수 없게 됐으면 전용 오류로 거절한다 — 앱이 그 코드를 보고 목록을 새로 받는다.
     *
     * @return 중간 하차 후보가 아니면 null(옛 경로 그대로)
     */
    private RideTruncationResolver.Resolved resolveTruncation(RerouteCandidateApply apply,
                                                              List<JourneyLeg> legs, Instant now) {
        if (!(apply instanceof RerouteCandidateApply.RemainingPathApply remaining)) {
            return null;
        }
        RerouteOrigin origin = remaining.origin();
        if (origin == null || origin.kind() != RerouteOriginKind.RIDE_INTERIOR) {
            return null;
        }
        JourneyLeg ride = legs.stream()
                .filter(leg -> leg.getLegIndex() == origin.legIndex())
                .findFirst()
                .orElse(null);
        RideTruncationResolver.Result result = truncationResolver.resolve(ride, origin, now);
        if (result.failed()) {
            log.debug("[JourneyReroute] 중간 하차를 반영할 수 없다 legIndex={} 사유={}",
                    origin.legIndex(), result.failureReason());
            metrics.recordMidRideAccept(result.failureReason());
            throw new BusinessException(JourneyErrorCode.REROUTE_ALIGHT_UNAVAILABLE);
        }
        return result.resolved();
    }

    /**
     * 내려서 갈아탈 <b>첫 차</b>를 아직 탈 수 있는가.
     *
     * <h3>못 물어보면 통과시킨다(fail open)</h3>
     * 하차 쪽과 <b>일부러 반대</b>다. 하차는 되돌릴 수 없어 모르면 거절하지만, 승차 추정이 틀려도
     * 손실은 정류장에서 더 기다리는 것뿐이다. 실시간이 잠깐 막혔다는 이유로 사용자가 고른 경로를
     * 거절하면 잃는 것이 더 크다.
     */
    private void requireStillBoardable(JourneyRevisionComposer.Composed composed,
                                       RideTruncationResolver.Resolved truncation, Instant now) {
        int minSlackSec = properties.getNextStop().getMissRiskMinSlackSec();
        if (minSlackSec <= 0) {
            return;
        }
        JourneyLegDraft wait = composed.legs().stream()
                .filter(leg -> leg.legIndex() > composed.fromLegIndex())
                .filter(leg -> leg.type().isWaiting())
                .findFirst()
                .orElse(null);
        if (wait == null) {
            // 걷기만으로 닿는 경로다. 탈 차가 없으니 놓칠 일도 없다.
            return;
        }
        Instant anchorAt = truncation.alightAt()
                .plusSeconds(properties.getNextStop().getBoardingBufferSec());
        BoardingEstimate estimate = estimateBoarding(wait, anchorAt, now);
        if (estimate == null) {
            metrics.recordMidRideAccept("boarding-unknown");
            return;
        }
        long slackSec = Duration.between(anchorAt, estimate.boardingAt()).getSeconds();
        if (!estimate.boardable() || slackSec < minSlackSec) {
            log.debug("[JourneyReroute] 수락 직전 첫 승차를 놓친다 legIndex={} slack={}s",
                    wait.legIndex(), slackSec);
            metrics.recordMidRideAccept("boarding-missed");
            throw new BusinessException(JourneyErrorCode.REROUTE_ALIGHT_UNAVAILABLE);
        }
    }

    /** 첫 대기 단계의 승차 시각 추정. 물어볼 수 없거나 터지면 null(위 fail open). */
    private BoardingEstimate estimateBoarding(JourneyLegDraft wait, Instant anchorAt, Instant now) {
        JourneyRouteOptionDraft option = wait.routeOptions().isEmpty()
                ? null : wait.routeOptions().get(0);
        if (option == null) {
            return null;
        }
        BoardingQuery query = new BoardingQuery(wait.type(), wait.startStopId(), wait.startName(),
                option.routeId(), option.boardingSeq(), option.wayCode());
        return boardingEstimators.stream()
                .filter(estimator -> estimator.supports(wait.type()))
                .findFirst()
                .map(estimator -> {
                    try {
                        return estimator.estimate(query, anchorAt, now,
                                properties.getNextStop().getArrivalBudgetMs()).estimate();
                    } catch (RuntimeException e) {
                        // 예측기는 예외를 올리는 계약이다. 수락 경로에서 그대로 터뜨리면 사용자가
                        // 고른 경로가 외부 장애 때문에 사라진다.
                        log.warn("[JourneyReroute] 수락 직전 승차 추정이 실패했다: {}", e.getMessage());
                        return null;
                    }
                })
                .orElse(null);
    }

    // ── 재확인 ──────────────────────────────────────────────────────────────

    /**
     * 제안이 만들어진 뒤 사용자가 누르기까지 최대 2분이 있다. 그 사이 여정은 계속 움직인다.
     *
     * <p>여기서 던지는 예외가 곧 <b>롤백</b>이다 — 위에서 제안을 이미 {@code ACCEPTED} 로 바꿨다.
     */
    private void requireStillApplicable(JourneySession journey, RerouteProposal proposal,
                                        RerouteCandidate candidate, Instant now) {
        boolean stale = proposal.getRouteRevision() != journey.getRouteRevision()
                || proposal.getBaseCommandVersion() != journey.getCommandVersion()
                || !proposal.getExpiresAt().isAfter(now)
                || fromLegIndexOf(candidate.apply()) < journey.getCurrentLegIndex();
        if (stale) {
            log.debug("[JourneyReroute] 수락 재확인 실패 journeyId={} proposalId={}",
                    journey.getId(), proposal.getId());
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
    }

    /**
     * 이미 수락된 제안에 다시 온 요청.
     *
     * <p>같은 후보면 재전송이다 — 앱은 응답을 못 받았을 뿐이고, 여정은 이미 그가 고른 대로 바뀌어
     * 있다. 오류로 돌려주면 앱이 되돌릴 방법이 없다. 다른 후보면 고를 수 없는 것을 고른 것이다.
     */
    private JourneySnapshot alreadyAccepted(JourneySession journey, Long proposalId,
                                            String candidateId) {
        RerouteProposal proposal = proposalRepository.findById(proposalId)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_NOT_FOUND));
        boolean sameCandidate = proposal.getStatus() == RerouteProposalStatus.ACCEPTED
                && candidateId.equals(proposal.getAcceptedCandidateId());
        if (!sameCandidate) {
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
        return queryService.snapshotOf(journey);
    }

    // ── 반영 ────────────────────────────────────────────────────────────────

    private void applyRevision(JourneySession journey, JourneyRevisionComposer.Composed composed,
                               RerouteCandidateApply apply,
                               RideTruncationResolver.Resolved truncation, Instant now) {
        int oldRevision = journey.getRouteRevision();
        int newRevision = oldRevision + 1;

        revisionRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                        journey.getId(), oldRevision)
                .ifPresent(revision -> revision.supersede(now));
        // 여정당 ACTIVE 개정본은 하나다. 새 행을 먼저 넣으면 자기 자신과 충돌한다.
        revisionRepository.flush();

        revisionRepository.save(JourneyRevision.builder()
                .journeyId(journey.getId())
                .routeRevision(newRevision)
                .status(JourneyRevisionStatus.ACTIVE)
                .reason(composed.reason())
                .routeSnapshot(objectMapper.valueToTree(composed.snapshot()))
                .activatedAt(now)
                .build());

        List<JourneyLeg> oldLegs = currentLegs(journey);
        List<JourneyLeg> newLegs = composed.keepsStructure()
                ? copyAll(oldLegs, newRevision)
                : rebuild(oldLegs, composed, newRevision, journey.getCurrentLegIndex(),
                truncation, now);
        legRepository.saveAllAndFlush(newLegs);

        saveRouteOptions(oldLegs, newLegs, composed, apply);
        saveTransfers(journey, composed, newRevision, oldRevision);

        // 지하철 고정은 단계와 함께 복제되지 않는다(별도 표). 구간이 완전히 같을 때만 명시적으로
        // 옮기고, 나머지 미완료 고정은 옛 개정본에서 접는다. 순서가 중요하다 - 먼저 접으면
        // 이어받을 원본이 이미 ABANDONED 다. 노선 후보 저장 뒤인 것도 방향(wayCode) 비교가
        // 새 단계의 선택 후보를 읽기 때문이다.
        subwayBindingLifecycle.inheritOnReroute(oldLegs, newLegs);
        subwayBindingLifecycle.abandonOpenOfRevision(journey.getId(), oldRevision,
                SubwayBindingAbandonReason.SUPERSEDED_BY_REVISION);

        journey.bumpRouteRevision();
        journey.bumpCommandVersion();
        // 한 여정에 수락은 한 번이다(PR-3 알림 계약). 사용자가 고른 길로 막 바꿨는데 또 바꾸라고
        // 알리면, 남은 이득보다 피로가 크다 — 새 경로가 나중에 또 위험해져도 알리지 않는다.
        journey.terminateReroute();
        // 경로가 통째로 바뀌었다. 다음 주기를 기다릴 이유가 없다.
        journey.scheduleEvaluationAt(now);
        sessionRepository.flush();

        // 이전 개정본을 근거로 만든 알림은 나가면 안 된다. 사유가 곧 운영 신호다.
        notificationRepository.cancelPendingByJourneyId(journey.getId(), "route-changed");
        proposalRepository.resolveStale(journey.getId(), newRevision, now, LocalDateTime.now(clock))
                .forEach(reason -> metrics.recordResolved(reason, 1));
    }

    /** 구조가 안 바뀌는 수락({@code ROUTE_OPTION}). 단계를 그대로 옮긴다. */
    private List<JourneyLeg> copyAll(List<JourneyLeg> oldLegs, int newRevision) {
        return oldLegs.stream().map(leg -> leg.copyToRevision(newRevision)).toList();
    }

    /**
     * 앞 구간은 복제하고 교체 지점부터는 새 단계로 만든다.
     *
     * <p>앞 구간을 재컴파일로 다시 만들지 않는 이유는 <b>진행도·증거·시각</b> 때문이다. 잃으면
     * 앱의 GPS 엔진이 진행 중인 단계를 처음부터 다시 센다.
     *
     * <p>중간 하차(PR-4)에는 <b>제3의 경우</b>가 하나 있다. 잘린 주행 단계는 번호가
     * {@code fromLegIndex} 와 같아 이 규칙대로면 새로 만들어지는데, 그것은 사용자가 <b>지금 타고
     * 있는</b> 단계라 진행도가 전멸한다. 그래서 복제한 뒤 목표만 앞당긴다
     * ({@link JourneyLeg#copyToRevisionTruncated}).
     */
    private List<JourneyLeg> rebuild(List<JourneyLeg> oldLegs,
                                     JourneyRevisionComposer.Composed composed,
                                     int newRevision, int currentLegIndex,
                                     RideTruncationResolver.Resolved truncation, Instant now) {
        int fromLegIndex = composed.fromLegIndex();
        Map<Integer, JourneyLeg> oldByIndex = new HashMap<>();
        oldLegs.forEach(leg -> oldByIndex.put(leg.getLegIndex(), leg));

        List<JourneyLeg> legs = new ArrayList<>(composed.legs().size());
        for (JourneyLegDraft draft : composed.legs()) {
            if (draft.legIndex() < fromLegIndex) {
                legs.add(oldByIndex.get(draft.legIndex()).copyToRevision(newRevision));
                continue;
            }
            if (truncation != null && composed.truncatedLegIndex() != null
                    && draft.legIndex() == composed.truncatedLegIndex()) {
                legs.add(truncated(oldByIndex.get(draft.legIndex()), draft, truncation,
                        newRevision, now));
                continue;
            }
            JourneyLeg leg = JourneyLeg.builder()
                    .journeyId(oldLegs.get(0).getJourneyId())
                    .routeRevision(newRevision)
                    .legIndex(draft.legIndex())
                    .type(draft.type())
                    .state(JourneyLegState.PENDING)
                    .startStopId(draft.startStopId())
                    .endStopId(draft.endStopId())
                    .sourceStartStopId(draft.sourceStartStopId())
                    .sourceEndStopId(draft.sourceEndStopId())
                    .startName(draft.startName())
                    .endName(draft.endName())
                    .plannedDurationSec(draft.plannedDurationSec())
                    .evidenceQuality(draft.evidenceQuality())
                    .build();
            if (draft.legIndex() == fromLegIndex && fromLegIndex == currentLegIndex) {
                inheritActivation(leg, oldByIndex.get(fromLegIndex), draft, now);
            }
            legs.add(leg);
        }
        return legs;
    }

    /**
     * 타고 가는 중인 단계를 더 이른 정류장에서 내리도록 자른다.
     *
     * <p>하차점·이름은 <b>합성본을 다시 쪼갠 초안</b>에서 가져온다(스냅샷과 단계가 같아야 한다는
     * 계약). 소요·남은 값은 <b>초안이 아니라 재확인 결과</b>에서 가져온다 — 스냅샷의 구간 소요는
     * 분 단위라 초안을 그대로 쓰면 최대 30초가 조용히 틀어진다.
     */
    private JourneyLeg truncated(JourneyLeg old, JourneyLegDraft draft,
                                 RideTruncationResolver.Resolved truncation, int newRevision,
                                 Instant now) {
        if (old == null) {
            // 자를 단계가 지금 개정본에 없다. 재확인이 통과했는데 여기서 없다면 읽은 값이 어긋난 것이다.
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
        int remainingSec = (int) Math.max(0,
                Duration.between(now, truncation.alightAt()).getSeconds());
        return old.copyToRevisionTruncated(newRevision, new JourneyLeg.Truncation(
                draft.endStopId(), draft.sourceEndStopId(), draft.endName(),
                truncation.rideSeconds(), truncation.alightAt(),
                truncation.remainingStops(), remainingSec, truncation.legFraction()));
    }

    /**
     * 사용자가 지금 그 단계에 서 있다. 상태를 잇되 <b>진행도·예측은 잇지 않는다</b>.
     *
     * <p>{@code activatedAt} 은 <b>단계 종류가 같을 때만</b> 잇는다. 옛 {@code WAIT} 의 시작 시각을
     * 새 {@code WALK} 의 출발 시각으로 쓰면 보행 ETA 가 과거에서 시작한다.
     */
    private void inheritActivation(JourneyLeg leg, JourneyLeg old, JourneyLegDraft draft,
                                   Instant now) {
        if (old == null || old.getState() != JourneyLegState.ACTIVE) {
            return;
        }
        boolean sameType = old.getType() == draft.type();
        leg.inheritActivation(JourneyLegState.ACTIVE,
                sameType && old.getActivatedAt() != null ? old.getActivatedAt() : now);
    }

    /**
     * 노선 후보를 저장하고 선택 노선을 다시 가리킨다.
     *
     * <p><b>복제한 단계의 {@code selectedRouteOptionId} 는 반드시 새 행으로 바꾼다.</b> 외래키는
     * 행이 있는지만 보므로 옛 개정본의 후보 행을 가리켜도 통과한다 — 그러면 개정본이 다른 두 단계가
     * 한 후보 행을 공유하는 유령 참조가 남는다.
     */
    private void saveRouteOptions(List<JourneyLeg> oldLegs, List<JourneyLeg> newLegs,
                                  JourneyRevisionComposer.Composed composed,
                                  RerouteCandidateApply apply) {
        Map<Integer, JourneyLeg> oldByIndex = new HashMap<>();
        oldLegs.forEach(leg -> oldByIndex.put(leg.getLegIndex(), leg));
        Map<Long, List<JourneyLegRouteOption>> oldOptions = loadOptions(oldLegs);
        RerouteCandidateApply.RouteOptionApply routeOption =
                apply instanceof RerouteCandidateApply.RouteOptionApply option ? option : null;

        for (JourneyLeg leg : newLegs) {
            List<JourneyLegRouteOption> saved = composed.keepsStructure()
                    || leg.getLegIndex() < composed.fromLegIndex()
                    ? copyOptions(oldByIndex.get(leg.getLegIndex()), oldOptions, leg)
                    : newOptions(composed, leg);

            // 교체 지점 leg 만 사용자가 고른 후보를 반영한다. 그 외 leg 은 기본(optionIndex 0).
            JourneyLegRouteOption selected =
                    routeOption != null && leg.getLegIndex() == composed.fromLegIndex()
                            ? chooseRouteOption(saved, leg, routeOption)
                            : selectByIndex(saved, 0);
            if (selected == null) {
                continue;
            }
            select(newLegs, leg, selected);
        }
        legRepository.flush();
    }

    /**
     * 고른 후보 행을 정한다.
     *
     * <p>저장 후보는 {@code optionIndex} 로 특정한다(여정 시작·기존 수락과 같은 규칙 — 무회귀). 저장 목록에
     * 없는 <b>동적 후보</b>({@link RerouteCandidateApply.RouteOptionApply#isDynamic()})는 그 번호로 못 찾으므로,
     * 이미 복제된 후보 중 같은 노선이 있으면 그것을, 없으면 apply 의 스냅샷으로 <b>새 행을 만들어</b> 고른다.
     * 동적 후보를 {@code optionIndex} 로 다루면 저장 첫 후보로 조용히 대체돼 다른 노선이 선택된다.
     */
    private JourneyLegRouteOption chooseRouteOption(List<JourneyLegRouteOption> saved, JourneyLeg leg,
                                                    RerouteCandidateApply.RouteOptionApply apply) {
        if (!apply.isDynamic()) {
            return selectByIndex(saved, apply.routeOptionIndex());
        }
        Optional<JourneyLegRouteOption> match = saved.stream()
                .filter(option -> apply.routeId() != null
                        && apply.routeId().equals(option.getRouteId())
                        && Objects.equals(apply.directionKey(), option.getDirectionKey()))
                .findFirst();
        if (match.isPresent()) {
            return match.get();
        }
        int nextIndex = saved.stream()
                .mapToInt(JourneyLegRouteOption::getOptionIndex).max().orElse(-1) + 1;
        // lane_index >= 0 제약. 동적 후보는 대응 lane 이 없어 새 option_index 로 채운다(원래 lane
        // 범위 밖이라 클라가 옛 lane graphic 을 잘못 붙이지 않는다).
        return routeOptionRepository.saveAndFlush(JourneyLegRouteOption.builder()
                .journeyLegId(leg.getId())
                .optionIndex(nextIndex)
                .laneIndex(apply.laneIndex() != null ? apply.laneIndex() : nextIndex)
                .routeId(apply.routeId())
                .sourceRouteId(apply.sourceRouteId())
                .routeName(apply.routeName())
                .directionKey(apply.directionKey())
                .wayCode(apply.wayCode())
                .boardingSeq(apply.boardingSeq())
                .alightingSeq(apply.alightingSeq())
                .build());
    }

    /** {@code optionIndex} 로 후보를 고르고, 없으면 첫 후보. 비어 있으면 null(호출부가 skip). */
    private static JourneyLegRouteOption selectByIndex(List<JourneyLegRouteOption> saved, int index) {
        if (saved.isEmpty()) {
            return null;
        }
        return saved.stream()
                .filter(option -> option.getOptionIndex() == index)
                .findFirst()
                .orElse(saved.get(0));
    }

    private List<JourneyLegRouteOption> copyOptions(JourneyLeg oldLeg,
                                                    Map<Long, List<JourneyLegRouteOption>> oldOptions,
                                                    JourneyLeg newLeg) {
        if (oldLeg == null) {
            return List.of();
        }
        List<JourneyLegRouteOption> source = oldOptions.getOrDefault(oldLeg.getId(), List.of());
        if (source.isEmpty()) {
            return List.of();
        }
        return routeOptionRepository.saveAllAndFlush(source.stream()
                .map(option -> JourneyLegRouteOption.builder()
                        .journeyLegId(newLeg.getId())
                        .optionIndex(option.getOptionIndex())
                        .laneIndex(option.getLaneIndex())
                        .routeId(option.getRouteId())
                        .sourceRouteId(option.getSourceRouteId())
                        .routeName(option.getRouteName())
                        .directionKey(option.getDirectionKey())
                        .wayCode(option.getWayCode())
                        .boardingSeq(option.getBoardingSeq())
                        .alightingSeq(option.getAlightingSeq())
                        .build())
                .toList());
    }

    private List<JourneyLegRouteOption> newOptions(JourneyRevisionComposer.Composed composed,
                                                   JourneyLeg leg) {
        List<JourneyRouteOptionDraft> drafts = composed.legs().stream()
                .filter(draft -> draft.legIndex() == leg.getLegIndex())
                .findFirst()
                .map(JourneyLegDraft::routeOptions)
                .orElse(List.of());
        if (drafts.isEmpty()) {
            return List.of();
        }
        return routeOptionRepository.saveAllAndFlush(drafts.stream()
                .map(draft -> JourneyLegRouteOption.builder()
                        .journeyLegId(leg.getId())
                        .optionIndex(draft.optionIndex())
                        .laneIndex(draft.laneIndex())
                        .routeId(draft.routeId())
                        .sourceRouteId(draft.sourceRouteId())
                        .routeName(draft.routeName())
                        .directionKey(draft.directionKey())
                        .wayCode(draft.wayCode())
                        .boardingSeq(draft.boardingSeq())
                        .alightingSeq(draft.alightingSeq())
                        .build())
                .toList());
    }

    /** 대기 단계와 짝 주행 단계가 <b>같은</b> 후보 행을 가리켜야 한다(여정 시작과 같은 규칙). */
    private void select(List<JourneyLeg> legs, JourneyLeg waitLeg, JourneyLegRouteOption option) {
        waitLeg.selectRouteOption(option.getId(), option.getRouteId());
        legs.stream()
                .filter(leg -> leg.getLegIndex() == waitLeg.getLegIndex() + 1)
                .filter(leg -> !leg.getType().isWaiting())
                .findFirst()
                .ifPresent(ride -> ride.selectRouteOption(option.getId(), option.getRouteId()));
    }

    /**
     * 환승을 새 개정본으로 옮긴다.
     *
     * <p>교체 지점 <b>앞</b>은 판정·알림 기록까지 복제하고, 그 뒤는 초기화한다. 뒤쪽 판정은 옛
     * 노선의 예측이고, 특히 {@code lastNotifiedStatus} 가 남으면 <b>새 위험을 알리지 못한다</b>.
     */
    private void saveTransfers(JourneySession journey, JourneyRevisionComposer.Composed composed,
                               int newRevision, int oldRevision) {
        List<TransferConnection> oldTransfers = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journey.getId(), oldRevision);
        if (composed.keepsStructure()) {
            transferRepository.saveAll(oldTransfers.stream()
                    .map(transfer -> transfer.copyToRevision(newRevision,
                            transfer.getToLegIndex() < composed.fromLegIndex()))
                    .toList());
            return;
        }

        Map<Integer, TransferConnection> oldByToLeg = new HashMap<>();
        oldTransfers.forEach(transfer -> oldByToLeg.put(transfer.getToLegIndex(), transfer));
        List<TransferConnection> transfers = new ArrayList<>(composed.transfers().size());
        for (TransferConnectionDraft draft : composed.transfers()) {
            TransferConnection old = oldByToLeg.get(draft.toLegIndex());
            boolean keep = draft.toLegIndex() < composed.fromLegIndex() && old != null;
            transfers.add(keep
                    ? old.copyToRevision(newRevision, true)
                    : TransferConnection.builder()
                            .journeyId(journey.getId())
                            .routeRevision(newRevision)
                            .transferIndex(draft.transferIndex())
                            .fromLegIndex(draft.fromLegIndex())
                            .walkLegIndex(draft.walkLegIndex())
                            .toLegIndex(draft.toLegIndex())
                            .status(watoo.grd.nextroute.domain.journey.entity.TransferStatus.UNKNOWN)
                            .build());
        }
        transferRepository.saveAll(transfers);
    }

    /**
     * 이력을 남긴다. {@code eventId} 는 <b>서버가 결정론으로</b> 만든다.
     *
     * <p>수락 요청에는 앱이 만든 {@code eventId} 가 없다. 같은 제안·후보에 대해 언제나 같은 값이
     * 나오게 두면 {@code uq_journey_event_id} 가 두 번째 삽입을 막아 준다 — 조건부 update 에 이은
     * 두 번째 안전장치다.
     */
    private void recordEvent(JourneySession journey, Long proposalId, String candidateId,
                             Instant now) {
        eventRepository.save(JourneyEvent.builder()
                .eventId(eventIdOf(proposalId, candidateId))
                .journeyId(journey.getId())
                .baseCommandVersion(journey.getCommandVersion() - 1)
                .routeRevision(journey.getRouteRevision() - 1)
                .type("REROUTE_ACCEPTED")
                .serverReceivedAt(now)
                .result(JourneyEventResult.APPLIED)
                .resultingCommandVersion(journey.getCommandVersion())
                .build());
    }

    static UUID eventIdOf(Long proposalId, String candidateId) {
        return UUID.nameUUIDFromBytes(
                (EVENT_ID_PREFIX + proposalId + ":" + candidateId).getBytes(StandardCharsets.UTF_8));
    }

    // ── 보조 ────────────────────────────────────────────────────────────────

    /**
     * 저장된 후보 중 사용자가 고른 것.
     *
     * <p><b>모르는 봉투 판은 없는 것으로 취급한다.</b> 새 계약으로 옛 행을 읽으면 필드 뜻이 어긋난
     * 채 수락까지 흘러가고, 그 결과는 사용자가 고르지 않은 경로로 여정이 바뀌는 것이다.
     */
    private RerouteCandidate candidateOf(RerouteProposal proposal, String candidateId) {
        RerouteCandidateEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(
                    proposal.getCandidates(), RerouteCandidateEnvelope.class);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보를 읽지 못했다 proposalId={}: {}",
                    proposal.getId(), e.getMessage());
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        if (!envelope.isCurrentVersion()) {
            throw new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND);
        }
        return envelope.candidates().stream()
                .filter(candidate -> candidateId.equals(candidate.candidateId()))
                .findFirst()
                .orElseThrow(() ->
                        new BusinessException(JourneyErrorCode.REROUTE_CANDIDATE_NOT_FOUND));
    }

    private int fromLegIndexOf(RerouteCandidateApply apply) {
        return apply instanceof RerouteCandidateApply.RemainingPathApply remaining
                ? remaining.fromLegIndex()
                : ((RerouteCandidateApply.RouteOptionApply) apply).waitLegIndex();
    }

    private List<JourneyLeg> currentLegs(JourneySession journey) {
        return legRepository.findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByLegIndex(
                journey.getId(), journey.getRouteRevision());
    }

    private watoo.grd.nextroute.application.route.dto.PathResult snapshotOf(JourneySession journey) {
        Optional<JourneyRevision> revision = revisionRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNull(
                        journey.getId(), journey.getRouteRevision());
        if (revision.isEmpty() || revision.get().getRouteSnapshot() == null) {
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
        try {
            return objectMapper.treeToValue(revision.get().getRouteSnapshot(),
                    watoo.grd.nextroute.application.route.dto.PathResult.class);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 개정본 스냅샷을 읽지 못했다 journeyId={}: {}",
                    journey.getId(), e.getMessage());
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }
    }

    private Map<Long, List<JourneyLegRouteOption>> loadOptions(List<JourneyLeg> legs) {
        List<Long> ids = legs.stream().map(JourneyLeg::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<JourneyLegRouteOption>> byLeg = new HashMap<>();
        routeOptionRepository.findByJourneyLegIdInAndDeletedAtIsNull(ids)
                .forEach(option -> byLeg
                        .computeIfAbsent(option.getJourneyLegId(), key -> new ArrayList<>())
                        .add(option));
        return byLeg;
    }
}
