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
import watoo.grd.nextroute.application.journey.dto.RerouteDecision;
import watoo.grd.nextroute.application.journey.dto.RerouteOrigin;
import watoo.grd.nextroute.application.journey.dto.RerouteOriginEnvelope;
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
 * <p>둘로 나누는 이유는 후보 생성(④)이 실시간 버스 API·ML·경로 검색을 부르기 때문이다. 그 호출을
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
    private final RerouteOriginResolver originResolver;
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
        // 접기 전에 센다(PR-3). 상태를 바꾼 뒤에는 어떤 행이 "알렸는데 답이 없었던" 것인지
        // 알 수 없고, 그 수가 곧 이 여정에 더 알려도 되는지의 근거다.
        int unanswered = proposalRepository.countUnansweredDue(journeyId, currentRevision, now);

        List<String> reasons = proposalRepository.resolveStale(
                journeyId, currentRevision, now, LocalDateTime.now(clock));
        countByReason(reasons);

        if (unanswered > 0) {
            sessionRepository.bumpRerouteIgnored(journeyId, unanswered);
            metrics.recordAlert("unanswered", unanswered);
        }
        return reasons.size();
    }

    /**
     * 사용자가 제안을 닫았다 — 거절 또는 무시(PR-3 알림 계약).
     *
     * <p><b>멱등이다.</b> 같은 요청이 두 번 와도 상태 전이는 한 번만 일어난다({@code ACTIVE} 일
     * 때만 바뀌는 한 문장). 두 번째 호출은 여정 상태도 건드리지 않는다 — 무시를 두 번 세면
     * 재전송 한 번이 알림 상한을 통째로 먹는다.
     *
     * <p>여정 상태를 함께 바꾸는 것이 이 메서드가 트랜잭션인 이유다. 제안만 닫고 여정 플래그를
     * 못 쓰면 다음 방아쇠가 곧바로 또 알린다 — 사용자가 방금 싫다고 말한 그 알림을.
     *
     * @param optInAlerts 거절하면서 "계속 알림 받기"를 켰는가. 무시에는 뜻이 없다
     * @return 이번 호출이 실제로 닫았으면 true
     */
    @Transactional
    public RerouteDecision close(Long journeyId, Long proposalId, RerouteProposalStatus decision,
                                 boolean optInAlerts, Instant now) {
        String reason = decision == RerouteProposalStatus.REJECTED ? "rejected" : "dismissed";
        int closed = proposalRepository.closeIfActive(proposalId, journeyId, decision.name(),
                reason, now, LocalDateTime.now(clock));

        Optional<JourneySession> found = sessionRepository.findById(journeyId);
        if (found.isEmpty()) {
            return new RerouteDecision(closed > 0, false, false);
        }
        JourneySession journey = found.get();
        if (closed == 0) {
            // 이미 닫힌 제안이다(재전송·만료). 여정 상태는 건드리지 않는다 — 무시를 두 번 세면
            // 재전송 한 번이 알림 상한을 통째로 먹는다.
            return RerouteDecision.unchanged(
                    journey.isRerouteTerminated(), journey.isRerouteAlertOptIn());
        }

        if (decision == RerouteProposalStatus.REJECTED) {
            if (optInAlerts) {
                // "이 제안은 싫지만 다음은 보여 달라." 종료가 아니라 계속이다.
                journey.optInRerouteAlert();
                metrics.recordAlert("opt-in");
            } else {
                journey.terminateReroute();
                metrics.recordAlert("rejected");
            }
        } else {
            // 무시는 의사표시가 아니다. 상한까지만 참는다.
            journey.recordRerouteIgnored();
            metrics.recordAlert("dismissed");
        }
        return new RerouteDecision(true, journey.isRerouteTerminated(),
                journey.isRerouteAlertOptIn());
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
        // 원점이 곧 주소다(stage5 2/2 — 옛 transfer_index 는 컬럼째 사라졌다). 못 풀면 저장하지
        // 않는다: 주소 없는 행은 "어디에 대한 제안인지"를 영영 말할 수 없고, 사용자에게 보여 줘도
        // 무엇을 하라는 것인지 가리킬 수 없다.
        Optional<RerouteOrigin> resolved = originResolver.resolveTransferWait(
                context.journeyId(), context.routeRevision(), context.transferIndex());
        if (resolved.isEmpty()) {
            metrics.recordRejected("origin-unresolved");
            return Optional.empty();
        }
        RerouteOrigin origin = resolved.get();

        if (!stillCurrent(context, origin)) {
            metrics.recordRejected("stale-context");
            return Optional.empty();
        }
        LocalDateTime auditNow = LocalDateTime.now(clock);
        countByReason(proposalRepository.resolveStale(
                context.journeyId(), context.routeRevision(), context.now(), auditNow));

        // 여정당 하나다(stage5). 자리를 비우는 규칙과 부분 unique 인덱스는 짝이라 함께 좁혔다.
        int replaced = proposalRepository.supersedeActive(
                context.journeyId(), context.now(), auditNow);
        metrics.recordResolved("replaced", replaced);

        List<RerouteCandidate> stored = candidates.stream()
                .limit(properties.getMaxCandidates())
                .map(candidate -> candidate.withCandidateId(UUID.randomUUID().toString()))
                .toList();

        RerouteProposal proposal = proposalRepository.save(RerouteProposal.builder()
                .journeyId(context.journeyId())
                .userId(context.userId())
                .routeRevision(context.routeRevision())
                .origin(toJson(origin))
                .kind(kind)
                .baseCommandVersion(context.commandVersion())
                .candidates(toJson(stored))
                .snapshotObservedAt(snapshotObservedAt)
                .expiresAt(expiresAt)
                .build());

        metrics.recordCreated(kind.name());
        return Optional.of(new RerouteProposalView(proposal.getId(), proposal.getJourneyId(),
                proposal.getRouteRevision(), origin,
                proposal.getKind(), proposal.getBaseCommandVersion(), proposal.getSnapshotObservedAt(),
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
     *   <li>진행 단계 — 이미 그 자리를 지났다. 사용자가 할 수 있는 일이 없는 제안이 된다.
     *       기준은 <b>원점의 leg 번호</b>이고, 원점을 못 푼 여정만 옛 환승 행으로 폴백한다</li>
     * </ul>
     *
     * <p><b>여정 행을 잠그고 읽는다.</b> 잠그지 않으면 읽기와 삽입 사이에 탑승 명령이 커밋될 수
     * 있어, 확인 자체가 "방금까지는 맞았던 사진"이 된다 — 이 확인이 막으려던 바로 그 경합이다.
     * 명령 처리({@code JourneyCommandService})가 같은 행을 같은 순서로 잠그므로 서로 줄을 선다.
     */
    private boolean stillCurrent(RerouteContext context, RerouteOrigin origin) {
        Optional<JourneySession> found = sessionRepository
                .findByIdAndUserIdForUpdate(context.journeyId(), context.userId());
        if (found.isEmpty()) {
            return false;
        }
        JourneySession journey = found.get();
        if (journey.getStatus() != JourneyStatus.ACTIVE
                || journey.getCommandVersion() != context.commandVersion()
                || journey.getRouteRevision() != context.routeRevision()) {
            return false;
        }
        // 원점이 곧 주소다. 환승 행을 다시 읽지 않는다 — 주행 중 정류장 원점에는 대응하는 환승
        // 행이 아예 없으므로, 그 모양이 오면 환승 기준 확인이 통째로 막아 버린다.
        return origin.legIndex() >= journey.getCurrentLegIndex();
    }

    /**
     * 후보가 <b>전부 지나가 버린</b> 제안을 접는다(PR-3 pull validate).
     *
     * <p>빈 목록을 살아 있는 제안으로 두면 여정당 하나뿐인 자리를 막아 새 제안이 만들어지지
     * 않는다. 무응답으로 세지 않는 것이 중요하다 — 사용자는 알림을 <b>열었고</b>, 다만 그 사이
     * 버스가 그 정류장들을 지나갔을 뿐이다.
     */
    @Transactional
    public void expireValidatedOut(Long proposalId, Instant now) {
        proposalRepository.findById(proposalId)
                .filter(RerouteProposal::isActive)
                .ifPresent(proposal -> {
                    proposal.expire(now, "all-passed");
                    metrics.recordResolved("all-passed", 1);
                });
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

    private JsonNode toJson(RerouteOrigin origin) {
        return objectMapper.valueToTree(RerouteOriginEnvelope.of(origin));
    }

    /**
     * 저장된 origin. <b>모르는 판이면 없는 것으로 친다</b> — 후보 봉투와 같은 이유다(계약이 바뀐
     * 행을 새 뜻으로 읽으면 엉뚱한 정류장을 가리킨다). 그때는 부르는 쪽이 옛 {@code transferIndex}
     * 로 폴백한다.
     */
    private RerouteOrigin readOrigin(RerouteProposal proposal) {
        if (proposal.getOrigin() == null || proposal.getOrigin().isNull()) {
            return null;
        }
        try {
            RerouteOriginEnvelope envelope =
                    objectMapper.treeToValue(proposal.getOrigin(), RerouteOriginEnvelope.class);
            if (!envelope.isCurrentVersion()) {
                log.warn("[JourneyReroute] 모르는 origin 계약 판 proposalId={} version={}",
                        proposal.getId(), envelope.version());
                return null;
            }
            return envelope.origin();
        } catch (Exception e) {
            log.warn("[JourneyReroute] origin 을 읽지 못했다 proposalId={}: {}",
                    proposal.getId(), e.getMessage());
            return null;
        }
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
        if (!envelope.isReadable()) {
            log.warn("[JourneyReroute] 모르는 후보 계약 판 proposalId={} version={}",
                    proposal.getId(), envelope.version());
            return Optional.empty();
        }
        return Optional.of(new RerouteProposalView(proposal.getId(), proposal.getJourneyId(),
                proposal.getRouteRevision(), readOrigin(proposal),
                proposal.getKind(), proposal.getBaseCommandVersion(), proposal.getSnapshotObservedAt(),
                proposal.getExpiresAt(), envelope.visibleCandidates()));
    }
}
