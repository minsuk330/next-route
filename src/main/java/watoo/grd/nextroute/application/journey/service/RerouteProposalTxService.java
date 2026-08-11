package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidate;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.dto.RerouteContext;
import watoo.grd.nextroute.application.journey.dto.RerouteProposalView;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalKind;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 제안의 <b>저장과 만료 전이만</b> 맡는다. 외부 호출은 {@link RerouteProposalService} 가 한다.
 *
 * <p>둘로 나누는 이유는 후보 생성(③④)이 실시간 버스 API·ML·ODsay 를 부르기 때문이다. 그 호출을
 * 트랜잭션 안에서 하면 응답을 기다리는 동안 DB 커넥션을 붙잡는다
 * ({@code JourneyStartService}/{@code JourneyStartTxService} 분리와 같은 모양).
 *
 * <p>수락 경로(⑤)도 재검증 전에 {@link #resolveStale} 을 부른다 — 만료 전이를 별도 클래스로
 * 빼지 않고 여기 public 메서드로 노출한 이유가 그것이다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteProposalTxService {

    private final RerouteProposalRepository proposalRepository;
    private final JourneySessionRepository sessionRepository;
    private final TransferConnectionRepository transferRepository;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 만료·개정본 어긋남을 정리한다. 조회·생성·수락 <b>세 진입점이 모두</b> 이것을 먼저 지난다.
     *
     * <p>지나갈 때 넘기는 이유는 부분 unique 때문이다 — 만료된 {@code ACTIVE} 행이 자리를 잡으면
     * 같은 환승의 새 제안이 영영 안 생긴다. 아무도 지나가지 않는 시간은
     * {@link RerouteProposalExpiryService} 가 맡는다.
     *
     * @return 정리한 행 수
     */
    @Transactional
    public int resolveStale(Long journeyId, int currentRevision, Instant now) {
        List<String> reasons = proposalRepository.resolveStale(
                journeyId, currentRevision, now, LocalDateTime.now(clock));
        countByReason(reasons);
        return reasons.size();
    }

    /**
     * 제안을 저장한다. 한 트랜잭션 안에서 "만료 전이 → supersede → insert" 를 끝낸다.
     *
     * <p>셋을 나누면 그 사이에 다른 요청이 들어와 부분 unique 위반이 난다. 나뉜 채로 위반이 나면
     * 앞선 제안은 이미 {@code SUPERSEDED} 인데 새 제안은 못 들어간 상태가 남는다 — 그 환승은
     * 살아 있는 제안이 하나도 없는 채로 사용자를 기다리게 된다.
     *
     * <p>{@code candidateId} 를 여기서 부여한다. provider 가 무엇을 넣었든 덮어쓴다 — ID 의
     * 유일성은 만드는 쪽이 아니라 저장하는 쪽이 책임진다.
     *
     * <h2>저장 직전에 여정을 다시 본다 (R3)</h2>
     * 후보를 만드는 동안(실시간·ML 왕복) 여정은 계속 움직인다. 사용자가 버스를 타 다음 단계로
     * 넘어갔거나, 다른 제안을 수락해 경로가 바뀌었을 수 있다. 넘겨받은 {@code context} 는 그
     * <b>왕복 이전</b>의 사진이라 그대로 믿으면 <b>지나간 환승에 대한 제안</b>이 저장된다.
     *
     * @return 저장한 제안. 그 사이 여정이 어긋났으면 <b>빈 결과</b>다 — 오류가 아니다. 사용자가
     *         이미 다음 단계로 갔거나 경로가 바뀐 것이고, 그때 제안을 심는 쪽이 틀렸다
     */
    @Transactional
    public Optional<RerouteProposalView> store(RerouteContext context, RerouteProposalKind kind,
                                               List<RerouteCandidate> candidates,
                                               Instant snapshotObservedAt, Instant expiresAt) {
        if (!stillCurrent(context)) {
            metrics.recordRejected("stale-context");
            return Optional.empty();
        }
        LocalDateTime auditNow = LocalDateTime.now(clock);
        countByReason(proposalRepository.resolveStale(
                context.journeyId(), context.routeRevision(), context.now(), auditNow));

        int replaced = proposalRepository.supersedeActive(
                context.journeyId(), context.transferIndex(), context.now(), auditNow);
        metrics.recordResolved("replaced", replaced);

        List<RerouteCandidate> stored = candidates.stream()
                .limit(properties.getMaxCandidates())
                .map(candidate -> candidate.withCandidateId(UUID.randomUUID().toString()))
                .toList();

        RerouteProposal proposal = proposalRepository.save(RerouteProposal.builder()
                .journeyId(context.journeyId())
                .userId(context.userId())
                .routeRevision(context.routeRevision())
                .transferIndex(context.transferIndex())
                .kind(kind)
                .baseCommandVersion(context.commandVersion())
                .candidates(toJson(stored))
                .snapshotObservedAt(snapshotObservedAt)
                .expiresAt(expiresAt)
                .build());

        metrics.recordCreated(kind.name());
        return Optional.of(new RerouteProposalView(proposal.getId(), proposal.getJourneyId(),
                proposal.getRouteRevision(), proposal.getTransferIndex(), proposal.getKind(),
                proposal.getBaseCommandVersion(), proposal.getSnapshotObservedAt(),
                proposal.getExpiresAt(), stored));
    }

    /**
     * 후보를 만드는 사이 여정이 그대로였는가.
     *
     * <p>넷을 본다. <b>넷이 각각 다른 사고를 막는다.</b>
     *
     * <ul>
     *   <li>{@code ACTIVE} — 여정이 끝났는데 제안을 심으면 끝난 여정에 살아 있는 제안이 남는다</li>
     *   <li>{@code commandVersion} — 사용자가 탑승·하차를 알렸다. 그 전의 판단이다</li>
     *   <li>{@code routeRevision} — 경로가 바뀌었다. 후보의 {@code legIndex} 가 다른 단계를 가리킨다</li>
     *   <li>진행 단계 — 이미 그 환승을 지났다. 사용자가 할 수 있는 일이 없는 제안이 된다</li>
     * </ul>
     */
    private boolean stillCurrent(RerouteContext context) {
        Optional<JourneySession> found = sessionRepository.findById(context.journeyId());
        if (found.isEmpty()) {
            return false;
        }
        JourneySession journey = found.get();
        if (journey.getStatus() != JourneyStatus.ACTIVE
                || journey.getCommandVersion() != context.commandVersion()
                || journey.getRouteRevision() != context.routeRevision()) {
            return false;
        }
        return transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        context.journeyId(), context.routeRevision(), context.transferIndex())
                .filter(transfer -> transfer.getToLegIndex() >= journey.getCurrentLegIndex())
                .isPresent();
    }

    /**
     * 살아 있는 제안 하나. 만료 전이를 <b>먼저 돌린 뒤</b> 부른다 — 그러지 않으면 TTL 이 지난
     * 행을 살아 있는 것으로 답한다.
     */
    @Transactional(readOnly = true)
    public Optional<RerouteProposalView> findActive(Long journeyId) {
        return proposalRepository
                .findFirstByJourneyIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                        journeyId, RerouteProposalStatus.ACTIVE)
                .flatMap(this::toView);
    }

    /** {@code ttl} 과 {@code route-changed} 를 갈라 센다. 뭉치면 왜 접혔는지 운영에서 못 가른다. */
    private void countByReason(List<String> reasons) {
        Map<String, Long> byReason = reasons.stream()
                .collect(Collectors.groupingBy(reason -> reason, Collectors.counting()));
        byReason.forEach(metrics::recordResolved);
    }

    private JsonNode toJson(List<RerouteCandidate> candidates) {
        return objectMapper.valueToTree(RerouteCandidateEnvelope.of(candidates));
    }

    /**
     * 저장된 행을 읽기 모델로 되돌린다.
     *
     * <p>모르는 봉투 판은 <b>없는 것으로 취급한다.</b> 새 계약으로 옛 행을 읽으면 필드 뜻이
     * 어긋난 채 수락까지 흘러가는데, 그 결과는 사용자가 고르지 않은 경로로 여정이 바뀌는 것이다.
     * 판을 올리는 변경은 살아 있는 행을 함께 접어야 한다.
     */
    private Optional<RerouteProposalView> toView(RerouteProposal proposal) {
        RerouteCandidateEnvelope envelope;
        try {
            envelope = objectMapper.treeToValue(
                    proposal.getCandidates(), RerouteCandidateEnvelope.class);
        } catch (Exception e) {
            log.warn("[JourneyReroute] 후보를 읽지 못했다 proposalId={}: {}",
                    proposal.getId(), e.getMessage());
            return Optional.empty();
        }
        if (!envelope.isCurrentVersion()) {
            log.warn("[JourneyReroute] 모르는 후보 계약 판 proposalId={} version={}",
                    proposal.getId(), envelope.version());
            return Optional.empty();
        }
        return Optional.of(new RerouteProposalView(proposal.getId(), proposal.getJourneyId(),
                proposal.getRouteRevision(), proposal.getTransferIndex(), proposal.getKind(),
                proposal.getBaseCommandVersion(), proposal.getSnapshotObservedAt(),
                proposal.getExpiresAt(), envelope.candidates()));
    }
}
