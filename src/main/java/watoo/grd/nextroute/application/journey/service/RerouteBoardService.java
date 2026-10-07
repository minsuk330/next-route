package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteBoardSlot;
import watoo.grd.nextroute.application.journey.dto.RerouteCandidateEnvelope;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposal;
import watoo.grd.nextroute.domain.journey.entity.RerouteProposalStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.RerouteProposalRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 재탐색 보드를 저장한다(#264, reroute-periodic-board PR-1).
 *
 * <p>보드는 여정 개정본마다 <b>ACTIVE 제안 한 행</b>이다(V51 부분 unique). 1분 안팎마다 새로 찾은
 * 결과를 {@link RerouteBoardMerger} 로 합쳐 그 행을 제자리에서 고친다. 매번 새 행을 넣으면 후보 id 가
 * 바뀌어 사용자가 누르는 사이 수락이 깨지고, 자리마다의 기억(숨김·재표시)이 끊긴다.
 *
 * <h2>잠금 순서 — 세션 → 제안 (수락과 같다)</h2>
 * 여정 행을 먼저 {@code FOR UPDATE} 로 읽어 "계산하는 동안 여정이 그대로였나"를 확인한다. 확인 없이
 * 쓰면 구간 전이 전에 계산한 후보가 전이 뒤에 저장된다. 그 다음 제안 행을 조건부로 쓴다
 * ({@code board_version} 낙관 잠금 + {@code status='ACTIVE'}). 수락이 먼저 이기면 상태가 바뀌어
 * 0행이 되고, 이 회차는 버린다.
 *
 * <h2>사건 기반 경로와의 관계</h2>
 * 기존 요청·알림 경로가 만든 v1 행이 남아 있으면 보드로 대체한다(supersede 후 새로 심음). 플래그가
 * 꺼져 있으면 이 서비스는 불리지 않는다 — 부르는 쪽(PR-2 스케줄러)이 플래그를 본다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RerouteBoardService {

    private final JourneySessionRepository sessionRepository;
    private final RerouteProposalRepository proposalRepository;
    private final JourneyRerouteProperties properties;
    private final JourneyRerouteMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 이번 회차 결과를 보드에 합쳐 저장한다.
     *
     * @return 어떻게 끝났는지와 합친 자리들. 저장하지 않았으면 자리는 비어 있다
     */
    @Transactional
    public Written write(BoardWrite write, Instant now) {
        Optional<JourneySession> found = sessionRepository
                .findByIdAndUserIdForUpdate(write.journeyId(), write.userId());
        if (found.isEmpty() || !stillCurrent(found.get(), write)) {
            return done(Outcome.STALE_CONTEXT, null);
        }
        RerouteBoardMerger merger = new RerouteBoardMerger(
                RerouteBoardMerger.Thresholds.from(properties.getBoard()),
                () -> UUID.randomUUID().toString());
        LocalDateTime auditNow = LocalDateTime.now(clock);

        Optional<RerouteProposal> active = proposalRepository
                .findFirstByJourneyIdAndStatusAndDeletedAtIsNullOrderByCreatedAtDesc(
                        write.journeyId(), RerouteProposalStatus.ACTIVE);
        Optional<List<RerouteBoardSlot>> board = active.flatMap(this::boardSlots);
        if (active.isPresent() && (board.isEmpty()
                || active.get().getRouteRevision() != write.routeRevision())) {
            // 사건 기반 경로가 남긴 v1 행이거나 이전 개정본의 보드다. 자리를 비우고 새로 심는다.
            metrics.recordResolved("replaced",
                    proposalRepository.supersedeActive(write.journeyId(), now, auditNow));
            active = Optional.empty();
            board = Optional.empty();
        }

        if (active.isPresent()) {
            RerouteBoardMerger.Result merged = merger.merge(board.get(), write.input(), now);
            int updated = proposalRepository.updateBoard(active.get().getId(),
                    active.get().getBoardVersion(), write.commandVersion(), toJson(merged.slots()),
                    write.snapshotObservedAt(), expiresAt(now), now, auditNow);
            if (updated == 1) {
                return done(Outcome.UPDATED, merged);
            }
            RerouteProposalStatus status = proposalRepository.findStatusById(active.get().getId())
                    .orElse(RerouteProposalStatus.EXPIRED);
            if (status == RerouteProposalStatus.ACTIVE) {
                // 다른 갱신이 먼저 썼다. 다음 회차가 그 위에서 다시 합친다.
                return done(Outcome.CONFLICT, null);
            }
            if (status == RerouteProposalStatus.ACCEPTED) {
                return done(Outcome.ACCEPTED_MEANTIME, null);
            }
            // 만료·교체로 자리가 비었다. 기다리지 않고 바로 새로 심는다 — 아니면 다음 회차까지
            // 보드가 빈다.
        }
        return insert(merger, write, now, auditNow);
    }

    private Written insert(RerouteBoardMerger merger, BoardWrite write, Instant now,
                           LocalDateTime auditNow) {
        RerouteBoardMerger.Result merged = merger.merge(List.of(), write.input(), now);
        List<Long> inserted = proposalRepository.insertBoard(write.journeyId(), write.userId(),
                write.routeRevision(), write.commandVersion(), toJson(merged.slots()),
                write.snapshotObservedAt(), expiresAt(now), now, auditNow);
        if (inserted.isEmpty()) {
            // 같은 순간 다른 쪽이 먼저 심었다. 다음 회차가 그 행을 고친다.
            return done(Outcome.CONFLICT, null);
        }
        return done(Outcome.INSERTED, merged);
    }

    /**
     * 계산하는 동안 여정이 그대로였나. {@code RerouteProposalTxService.stillCurrent} 와 같은 넷을 본다
     * — 끝난 여정, 탑승·하차 명령, 경로 개정, 진행 단계.
     */
    private boolean stillCurrent(JourneySession journey, BoardWrite write) {
        return journey.getStatus() == JourneyStatus.ACTIVE
                && journey.getCommandVersion() == write.commandVersion()
                && journey.getRouteRevision() == write.routeRevision()
                && journey.getCurrentLegIndex() == write.currentLegIndex();
    }

    /** 보드(v2) 행이면 자리 목록. v1·모르는 판·읽기 실패는 비어 있다(보드가 아니다). */
    private Optional<List<RerouteBoardSlot>> boardSlots(RerouteProposal proposal) {
        try {
            RerouteCandidateEnvelope envelope = objectMapper.treeToValue(
                    proposal.getCandidates(), RerouteCandidateEnvelope.class);
            if (!envelope.isBoard()) {
                return Optional.empty();
            }
            return Optional.of(envelope.slots() == null ? List.of() : envelope.slots());
        } catch (Exception e) {
            log.warn("[JourneyReroute] 보드를 읽지 못했다 proposalId={}: {}",
                    proposal.getId(), e.getMessage());
            return Optional.empty();
        }
    }

    private Instant expiresAt(Instant now) {
        return now.plus(properties.getBoard().getTtl());
    }

    private String toJson(List<RerouteBoardSlot> slots) {
        try {
            return objectMapper.writeValueAsString(RerouteCandidateEnvelope.ofBoard(slots));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("보드를 직렬화하지 못했다", e);
        }
    }

    private Written done(Outcome outcome, RerouteBoardMerger.Result merged) {
        metrics.recordBoardWrite(outcome.tag());
        if (merged == null) {
            return new Written(outcome, List.of(), Map.of());
        }
        merged.transitions().forEach((transition, count) ->
                metrics.recordBoardSlot(transition.name().toLowerCase(), count));
        return new Written(outcome, merged.slots(), merged.transitions());
    }

    /**
     * 이번 회차의 저장 요청.
     *
     * @param routeRevision      계산에 쓴 개정본
     * @param commandVersion     계산에 쓴 명령 번호. 저장 행의 기준으로 다시 찍힌다
     * @param currentLegIndex    계산할 때 진행 중이던 단계
     * @param snapshotObservedAt 계산에 쓴 실시간 관측 시각
     */
    public record BoardWrite(Long journeyId, Long userId, int routeRevision, long commandVersion,
                             int currentLegIndex, Instant snapshotObservedAt,
                             RerouteBoardMerger.Input input) {
    }

    public record Written(Outcome outcome, List<RerouteBoardSlot> slots,
                          Map<RerouteBoardMerger.Transition, Integer> transitions) {

        public boolean stored() {
            return outcome == Outcome.INSERTED || outcome == Outcome.UPDATED;
        }
    }

    public enum Outcome {
        INSERTED, UPDATED, STALE_CONTEXT, CONFLICT, ACCEPTED_MEANTIME;

        String tag() {
            return name().toLowerCase().replace('_', '-');
        }
    }
}
