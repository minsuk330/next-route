package watoo.grd.nextroute.application.journey.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateApply;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateDisplay;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginKind;
import watoo.grd.nextroute.application.journey.port.out.RerouteCandidateProvider;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 여정 하나의 보드를 다시 찾는다(#264, reroute-periodic-board PR-2).
 *
 * <h2>후보 원천은 임시다</h2>
 * PR-2 는 <b>기존 공급자</b>(다음 환승 기준, 서울 중간 정류장 + 계획 하차점)를 그대로 돌려 자리별 평가로
 * 옮긴다. 그래서 아직 다음이 안 된다 — 환승 없는 여정·걷기·기다리기 기점(PR-3), 문턱 미만 후보의 숨김
 * 판정(기존 공급자가 300초 미만을 아예 내보내지 않아 "평가 못 함"으로 보인다, PR-3), 근거 집계(PR-3).
 * 보드 플래그는 PR-5 전에 켜지 않으므로 운영 영향은 없다.
 *
 * <p>공급자 목록을 직접 받는다. {@code RerouteProposalService.collect} 를 쓰면 그 서비스가 다시 이 서비스를
 * 부르는 사용자 요청 경로(보드 모드)와 순환한다. 순회 규칙(priority 순, 첫 비어 있지 않은 종류)은 같다.
 */
@Slf4j
@Service
public class RerouteBoardRefreshService {

    private final JourneySessionRepository sessionRepository;
    private final TransferConnectionRepository transferRepository;
    private final List<RerouteCandidateProvider> providers;
    private final RerouteBoardService boardService;
    private final Clock clock;

    public RerouteBoardRefreshService(JourneySessionRepository sessionRepository,
                                      TransferConnectionRepository transferRepository,
                                      List<RerouteCandidateProvider> providers,
                                      RerouteBoardService boardService,
                                      Clock clock) {
        this.sessionRepository = sessionRepository;
        this.transferRepository = transferRepository;
        this.providers = providers.stream()
                .sorted(Comparator.comparingInt(RerouteCandidateProvider::priority))
                .toList();
        this.boardService = boardService;
        this.clock = clock;
    }

    /**
     * @return 저장 결과. 끝난 여정이면 비어 있다
     */
    public Optional<RerouteBoardService.Written> refresh(Long journeyId) {
        Optional<JourneySession> found = sessionRepository.findById(journeyId)
                .filter(journey -> journey.getDeletedAt() == null)
                .filter(journey -> journey.getStatus() == JourneyStatus.ACTIVE);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        JourneySession journey = found.get();
        Instant now = Instant.now(clock);

        Map<String, RerouteBoardMerger.Evaluated> evaluated = new HashMap<>();
        Instant observedAt = now;
        Optional<TransferConnection> next = nextTransfer(journey);
        if (next.isPresent()) {
            RerouteContext context = new RerouteContext(journey.getId(), journey.getUserId(),
                    journey.getRouteRevision(), journey.getCommandVersion(),
                    next.get().getTransferIndex(), now);
            for (RerouteCandidate candidate : findCandidates(context)) {
                toEvaluated(candidate).ifPresent(fresh ->
                        evaluated.merge(fresh.slotKey(), fresh,
                                (a, b) -> a.gainSec() >= b.gainSec() ? a : b));
                Instant candidateObservedAt = candidate.display().dataObservedAt();
                if (candidateObservedAt != null && candidateObservedAt.isBefore(observedAt)) {
                    observedAt = candidateObservedAt;
                }
            }
        }
        return Optional.of(boardService.write(new RerouteBoardService.BoardWrite(
                journey.getId(), journey.getUserId(), journey.getRouteRevision(),
                journey.getCommandVersion(), journey.getCurrentLegIndex(), observedAt,
                new RerouteBoardMerger.Input(evaluated, Set.of())), now));
    }

    /** 아직 지나지 않은 첫 환승. 평가기의 {@code isUpcoming} 과 같은 기준이다. */
    private Optional<TransferConnection> nextTransfer(JourneySession journey) {
        return transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        journey.getId(), journey.getRouteRevision())
                .stream()
                .filter(transfer -> transfer.getToLegIndex() >= journey.getCurrentLegIndex())
                .findFirst();
    }

    /**
     * 공급자 사다리. 하나가 터져도 다음으로 간다 — 터진 공급자의 자리는 "평가 못 함"이라 보드가 유지한다.
     */
    private List<RerouteCandidate> findCandidates(RerouteContext context) {
        for (RerouteCandidateProvider provider : providers) {
            try {
                List<RerouteCandidate> candidates = provider.findCandidates(context);
                if (!candidates.isEmpty()) {
                    return candidates;
                }
            } catch (RuntimeException e) {
                log.warn("[RerouteBoard] 후보 생성 실패 kind={} journeyId={}: {}",
                        provider.kind(), context.journeyId(), e.getMessage());
            }
        }
        return List.of();
    }

    /** 후보 하나를 그 하차 자리의 평가로 옮긴다. 하차 정보가 없는 옛 모양은 자리를 정할 수 없어 버린다. */
    private Optional<RerouteBoardMerger.Evaluated> toEvaluated(RerouteCandidate candidate) {
        RerouteCandidateDisplay.AlightStop alight = candidate.display().alight();
        if (alight == null || alight.stopId() == null
                || !(candidate.apply() instanceof RerouteCandidateApply.RemainingPathApply remaining)) {
            return Optional.empty();
        }
        String slotKey = RerouteBoardSlot.stopKey(null, alight.stopId());
        RerouteOriginKind kind = alight.planned()
                ? RerouteOriginKind.TRANSFER_WAIT : RerouteOriginKind.RIDE_INTERIOR;
        // 근거(realtimeOnly)는 PR-3 이 집계한다. 그 전에는 보수적으로 ML 문턱(180초)을 탄다.
        // 기존 공급자는 환승이 늘어나는 후보를 이미 버리므로 늘어난 대기는 0 이다.
        return Optional.of(new RerouteBoardMerger.Evaluated(slotKey, kind, candidate,
                candidate.display().etaGainSec(), false, 0,
                RerouteRouteFingerprint.of(slotKey, remaining.path()),
                alight.alightAt()));
    }
}
