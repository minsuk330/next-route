package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private final JourneyRevisionComposer composer;
    private final JourneyQueryService queryService;
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
        JourneyRevisionComposer.Result composed = composer.compose(
                snapshotOf(journey), currentLegs(journey), candidate.apply());
        if (composed.failed()) {
            metrics.recordCandidateFiltered(composed.failureReason());
            throw new BusinessException(JourneyErrorCode.REROUTE_PROPOSAL_STALE);
        }

        applyRevision(journey, composed.composed(), candidate.apply(), now);
        recordEvent(journey, proposalId, candidateId, now);
        metrics.recordResolved("accepted", 1);
        return queryService.snapshotOf(journey);
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
                               RerouteCandidateApply apply, Instant now) {
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
                : rebuild(oldLegs, composed, newRevision, journey.getCurrentLegIndex(), now);
        legRepository.saveAllAndFlush(newLegs);

        saveRouteOptions(oldLegs, newLegs, composed, apply);
        saveTransfers(journey, composed, newRevision, oldRevision);

        journey.bumpRouteRevision();
        journey.bumpCommandVersion();
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
     */
    private List<JourneyLeg> rebuild(List<JourneyLeg> oldLegs,
                                     JourneyRevisionComposer.Composed composed,
                                     int newRevision, int currentLegIndex, Instant now) {
        int fromLegIndex = composed.fromLegIndex();
        Map<Integer, JourneyLeg> oldByIndex = new HashMap<>();
        oldLegs.forEach(leg -> oldByIndex.put(leg.getLegIndex(), leg));

        List<JourneyLeg> legs = new ArrayList<>(composed.legs().size());
        for (JourneyLegDraft draft : composed.legs()) {
            if (draft.legIndex() < fromLegIndex) {
                legs.add(oldByIndex.get(draft.legIndex()).copyToRevision(newRevision));
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
        Integer chosenOptionIndex = apply instanceof RerouteCandidateApply.RouteOptionApply option
                ? option.routeOptionIndex() : null;

        for (JourneyLeg leg : newLegs) {
            List<JourneyLegRouteOption> saved = composed.keepsStructure()
                    || leg.getLegIndex() < composed.fromLegIndex()
                    ? copyOptions(oldByIndex.get(leg.getLegIndex()), oldOptions, leg)
                    : newOptions(composed, leg);
            if (saved.isEmpty()) {
                continue;
            }
            int selectedIndex = leg.getLegIndex() == composed.fromLegIndex()
                    && chosenOptionIndex != null ? chosenOptionIndex : 0;
            JourneyLegRouteOption selected = saved.stream()
                    .filter(option -> option.getOptionIndex() == selectedIndex)
                    .findFirst()
                    .orElse(saved.get(0));
            select(newLegs, leg, selected);
        }
        legRepository.flush();
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
