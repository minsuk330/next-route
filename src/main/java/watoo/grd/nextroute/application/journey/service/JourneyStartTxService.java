package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.dto.JourneyLegDraft;
import watoo.grd.nextroute.application.journey.dto.JourneyPlan;
import watoo.grd.nextroute.application.journey.dto.JourneyRouteOptionDraft;
import watoo.grd.nextroute.application.journey.dto.JourneySnapshot;
import watoo.grd.nextroute.application.journey.dto.TransferConnectionDraft;
import watoo.grd.nextroute.application.journey.exception.ActiveJourneyExistsException;
import watoo.grd.nextroute.domain.journey.entity.JourneyEndReason;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevision;
import watoo.grd.nextroute.domain.journey.entity.JourneyRevisionStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneyRevisionRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.user.entity.User;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * 여정을 실제로 DB 에 앉히는 트랜잭션.
 *
 * <p>{@link JourneyStartService} 와 별도 빈으로 둔다. 같은 클래스 안에서 부르면 스프링의
 * 트랜잭션 프록시를 지나가지 않아 {@code @Transactional} 이 조용히 무효가 된다.
 *
 * <p>이 트랜잭션은 짧아야 한다. Redis 조회와 경로 쪼개기는 바깥에서 끝내고 들어온다 — 사용자
 * 행을 잠근 채 외부 저장소를 기다리면 같은 사람의 다음 요청이 그만큼 밀린다.
 */
@Service
@RequiredArgsConstructor
public class JourneyStartTxService {

    /** 앱이 안내를 맡는 기간. 이 시각까지 소식이 없으면 나중에 서버가 여정을 접는다(Phase 4). */
    private static final Duration GUIDANCE_LEASE = Duration.ofMinutes(3);

    private final UserRepository userRepository;
    private final JourneySessionRepository sessionRepository;
    private final JourneyRevisionRepository revisionRepository;
    private final JourneyLegRepository legRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final TransferConnectionRepository transferConnectionRepository;
    private final JourneyQueryService queryService;

    /**
     * 저장 순서는 고정이다.
     *
     * <ol>
     *   <li>사용자 행 잠금 — 같은 사람의 동시 요청을 한 줄로 세운다</li>
     *   <li>기존 여정 확인 — 있으면 갈아탈지 판단</li>
     *   <li>여정·경로 개정본·단계 저장</li>
     *   <li>노선 후보 저장 → 후보가 하나면 짝이 되는 두 단계에 확정</li>
     *   <li>환승 연결 저장</li>
     * </ol>
     *
     * <p>단계가 후보를 가리키고 후보도 단계를 가리키는 순환이라 한 번의 저장으로 끝낼 수 없다.
     * 단계를 먼저 저장해 번호를 받고, 그 번호로 후보를 저장해 다시 번호를 받아야 한다.
     */
    @Transactional
    public JourneySnapshot start(Long userId, JourneyPlan plan, JsonNode routeSnapshot,
                                 JsonNode destinationSummary, boolean replace, Instant now) {
        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new NoSuchElementException("사용자를 찾을 수 없다: " + userId));

        sessionRepository.findByUserIdAndStatusAndDeletedAtIsNull(user.getId(), JourneyStatus.ACTIVE)
                .ifPresent(existing -> replaceOrReject(existing, replace, now));

        JourneySession session = sessionRepository.saveAndFlush(JourneySession.builder()
                .userId(user.getId())
                .startedAt(now)
                .guidanceLeaseUntil(now.plus(GUIDANCE_LEASE))
                .routeSnapshot(routeSnapshot)
                .destinationSummary(destinationSummary)
                .build());

        revisionRepository.save(JourneyRevision.builder()
                .journeyId(session.getId())
                .routeRevision(session.getRouteRevision())
                .status(JourneyRevisionStatus.ACTIVE)
                .routeSnapshot(routeSnapshot)
                .activatedAt(now)
                .build());

        List<JourneyLeg> legs = saveLegs(session, plan);
        saveRouteOptions(plan, legs);
        saveTransfers(session, plan);

        return queryService.snapshotOf(session);
    }

    /**
     * 기존 여정을 갈아탄다. 명시적으로 요청했을 때만이다.
     *
     * <p>{@code flush()} 로 취소를 먼저 DB 에 보낸다. 새 여정을 넣을 때까지 미루면 "사람당 진행
     * 중인 여정 하나" 인덱스가 <b>자기 자신</b>과 충돌한다.
     */
    private void replaceOrReject(JourneySession existing, boolean replace, Instant now) {
        if (!replace) {
            throw new ActiveJourneyExistsException(queryService.snapshotOf(existing));
        }
        existing.end(JourneyStatus.CANCELED, JourneyEndReason.REPLACED, now);
        sessionRepository.saveAndFlush(existing);
    }

    private List<JourneyLeg> saveLegs(JourneySession session, JourneyPlan plan) {
        List<JourneyLeg> legs = new ArrayList<>(plan.legs().size());
        for (JourneyLegDraft draft : plan.legs()) {
            legs.add(JourneyLeg.builder()
                    .journeyId(session.getId())
                    .routeRevision(session.getRouteRevision())
                    .legIndex(draft.legIndex())
                    .type(draft.type())
                    .state(draft.state())
                    .startStopId(draft.startStopId())
                    .endStopId(draft.endStopId())
                    .sourceStartStopId(draft.sourceStartStopId())
                    .sourceEndStopId(draft.sourceEndStopId())
                    .startName(draft.startName())
                    .endName(draft.endName())
                    .plannedDurationSec(draft.plannedDurationSec())
                    .evidenceQuality(draft.evidenceQuality())
                    .build());
        }
        return legRepository.saveAllAndFlush(legs);
    }

    /**
     * 노선 후보를 저장하고, 후보가 하나뿐인 구간만 탈 노선을 확정한다.
     *
     * <p>후보가 여럿이면 비워 둔다. 서버가 먼저 하나를 고르면 사용자가 실제로 탄 노선과 어긋나고,
     * 그 어긋남은 도착 예측이 이상해질 때에야 드러난다.
     */
    private void saveRouteOptions(JourneyPlan plan, List<JourneyLeg> legs) {
        for (JourneyLegDraft draft : plan.legs()) {
            if (draft.routeOptions().isEmpty()) {
                continue;
            }
            JourneyLeg waitLeg = legs.get(draft.legIndex());
            List<JourneyLegRouteOption> saved = routeOptionRepository.saveAllAndFlush(
                    draft.routeOptions().stream()
                            .map(option -> toEntity(waitLeg, option))
                            .toList());

            if (!draft.hasSingleRouteOption()) {
                continue;
            }
            Long selected = saved.get(0).getId();
            waitLeg.selectRouteOption(selected);
            if (draft.pairedLegIndex() != null) {
                legs.get(draft.pairedLegIndex()).selectRouteOption(selected);
            }
        }
        legRepository.flush();
    }

    private JourneyLegRouteOption toEntity(JourneyLeg leg, JourneyRouteOptionDraft draft) {
        return JourneyLegRouteOption.builder()
                .journeyLegId(leg.getId())
                .optionIndex(draft.optionIndex())
                .laneIndex(draft.laneIndex())
                .routeId(draft.routeId())
                .sourceRouteId(draft.sourceRouteId())
                .routeName(draft.routeName())
                .wayCode(draft.wayCode())
                .boardingSeq(draft.boardingSeq())
                .alightingSeq(draft.alightingSeq())
                .build();
    }

    private void saveTransfers(JourneySession session, JourneyPlan plan) {
        if (plan.transfers().isEmpty()) {
            return;
        }
        List<TransferConnection> transfers = new ArrayList<>(plan.transfers().size());
        for (TransferConnectionDraft draft : plan.transfers()) {
            transfers.add(TransferConnection.builder()
                    .journeyId(session.getId())
                    .routeRevision(session.getRouteRevision())
                    .transferIndex(draft.transferIndex())
                    .fromLegIndex(draft.fromLegIndex())
                    .walkLegIndex(draft.walkLegIndex())
                    .toLegIndex(draft.toLegIndex())
                    // 위험도는 실시간 도착 정보를 보는 Phase 3 이 채운다. 지금은 판단 근거가 없다.
                    .status(TransferStatus.UNKNOWN)
                    .build());
        }
        transferConnectionRepository.saveAll(transfers);
    }
}
