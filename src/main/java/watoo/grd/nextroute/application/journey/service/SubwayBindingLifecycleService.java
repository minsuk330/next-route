package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingAbandonReason;
import watoo.grd.nextroute.application.journey.observability.SubwayBindingMetrics;
import watoo.grd.nextroute.application.journey.observability.SubwayRingMetrics;
import watoo.grd.nextroute.application.journey.port.out.SubwayRingPort;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 고정의 <b>수명주기</b>. 여정이 끝나거나 개정본이 오를 때 미완료 고정을 정리하고, 구간이 그대로면
 * 이어받는다.
 *
 * <h2>왜 한 곳에 모으나</h2>
 *
 * <p>여정이 끝나는 길이 넷이다 — 사용자 종료·lease 만료 배치·여정 갈아타기·재탐색 개정본. 각자
 * 자기 자리에서 접으면 넷 중 하나를 빠뜨렸을 때 그 경로만 조용히 새고, 그 고정은
 * <b>영원히 남는다</b>(이 표는 행을 지우지 않는다).
 *
 * <h2>부르는 쪽의 트랜잭션에 올라탄다</h2>
 *
 * <p>여기에 {@code @Transactional} 을 걸지 않는다. 네 접점 모두 이미 여정 세션 행을 잠근 채
 * 트랜잭션 안에 있고, 정리는 그 트랜잭션과 <b>함께 커밋되거나 함께 되돌아가야</b> 한다. 따로
 * 열면 "여정은 끝났는데 고정은 살아 있는" 창이 생긴다.
 *
 * <p>잠금 순서도 그래서 지켜진다 — {@code journey_session} → {@code subway_leg_binding}. 접는
 * 일은 단일 UPDATE 문장이라 읽고 고치는 창이 없고, 그래서 따로 행을 잡지 않는다.
 *
 * <h2>기능 스위치를 보지 않는다</h2>
 *
 * <p>{@code journey.subway.binding.enabled} 를 끈 뒤에도 켜져 있던 동안 만들어진 행은 남는다.
 * 정리까지 스위치에 묶으면 그 행들이 영영 미완료로 남는다. 접는 질의는 대상이 없으면 0행이라
 * 꺼진 상태에서의 비용은 문장 하나다. 진행 링도 같은 이유로 스위치를 보지 않는다.
 *
 * <h2>진행 링은 커밋 뒤에 지운다</h2>
 *
 * <p>고정 정리는 부르는 쪽 트랜잭션과 함께 되돌아가야 하지만, Redis 삭제는 되돌아가지 않는다.
 * 트랜잭션 안에서 지우면 롤백됐을 때 <b>링만 사라진다</b>.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayBindingLifecycleService {

    /** 근거를 되찾을 때 거슬러 읽는 감사행 수. 한 단계의 관측·판단이 이 안에 들어온다. */
    private static final int AUDIT_SCAN_LIMIT = 100;

    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayBindingEventRepository eventRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final JourneySubwayBindingProperties properties;
    private final SubwayBindingMetrics metrics;
    private final SubwayRingMetrics ringMetrics;
    private final SubwayRingPort ringPort;
    private final SubwayRingPushEnqueueService pushEnqueueService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    // ── 접기 ────────────────────────────────────────────────────────────────

    /** 그 여정의 미완료 고정을 접는다. {@code CONFIRMED} 는 그대로 둔다. */
    public int abandonOpen(Long journeyId, SubwayBindingAbandonReason reason) {
        int abandoned = bindingRepository.abandonOpenByJourney(journeyId, LocalDateTime.now(clock));
        report(abandoned, reason);
        endLiveActivity(List.of(journeyId));
        clearRingsAfterCommit(List.of(journeyId));
        return abandoned;
    }

    /** 만료 배치용. 여정마다 문장을 날리면 배치 하나가 그 수만큼 왕복한다. */
    public int abandonOpen(Collection<Long> journeyIds, SubwayBindingAbandonReason reason) {
        if (journeyIds.isEmpty()) {
            return 0;
        }
        int abandoned = bindingRepository.abandonOpenByJourneyIn(
                journeyIds, LocalDateTime.now(clock));
        report(abandoned, reason);
        endLiveActivity(journeyIds);
        clearRingsAfterCommit(journeyIds);
        return abandoned;
    }

    /**
     * 옛 개정본의 미완료 고정만 접는다.
     *
     * <p>계승을 <b>먼저</b> 부른 뒤에 부른다. 순서를 뒤집으면 이어받을 원본이 이미
     * {@code ABANDONED} 라 아무것도 옮겨지지 않는다.
     */
    public int abandonOpenOfRevision(Long journeyId, int routeRevision,
                                     SubwayBindingAbandonReason reason) {
        int abandoned = bindingRepository.abandonOpenByJourneyAndRevision(
                journeyId, routeRevision, LocalDateTime.now(clock));
        report(abandoned, reason);
        clearRingsAfterCommit(List.of(journeyId));
        return abandoned;
    }

    private void report(int abandoned, SubwayBindingAbandonReason reason) {
        metrics.recordAbandoned(reason, abandoned);
        if (abandoned > 0) {
            log.debug("[SubwayBinding] 미완료 고정 {}건 접음 reason={}", abandoned, reason);
        }
    }

    // ── 잠금화면 종료 ───────────────────────────────────────────────────────

    /**
     * 여정이 끝났으니 잠금화면 위젯도 끝내라고 보낸다.
     *
     * <p><b>개정본이 오르는 경로에서는 부르지 않는다.</b> 재탐색은 여정이 끝난 것이 아니라 경로가
     * 바뀐 것이고, Activity 는 여정 단위라 그대로 이어진다 — 여기서 종료를 보내면 사용자는
     * 재탐색을 수락할 때마다 잠금화면을 잃는다.
     *
     * <p>같은 트랜잭션에서 대기열 행을 만든다. 종료가 유실되면 잠금화면에 <b>죽은 안내가 그대로
     * 남고</b>, 앱이 꺼져 있으면 사용자가 지울 방법도 없다.
     */
    private void endLiveActivity(Collection<Long> journeyIds) {
        pushEnqueueService.enqueueTerminal(journeyIds);
    }

    // ── 진행 링 정리 ────────────────────────────────────────────────────────

    /**
     * 링을 <b>커밋 뒤에</b> 지운다.
     *
     * <p>트랜잭션 안에서 지우면 그 트랜잭션이 되돌아갔을 때 <b>링만 사라진다</b> — 여정은 그대로
     * 살아 있는데 화면의 진행 링만 죽고, 다음 tick 이 0 부터 다시 만들어 사용자에게는 링이 뒤로
     * 감긴 것으로 보인다. DB 정리와 달리 Redis 삭제는 롤백되지 않는다.
     *
     * <p>계승(재탐색)에서도 옛 키를 새 키로 <b>옮기지 않는다</b>. 다음 tick 이 새 고정 세대의
     * 키로 다시 만든다 — 옮기다 틀리면 옛 구간의 진행률이 새 경로에 붙는다.
     *
     * <p>지우지 못해도 여정 종료를 막지 않는다. TTL 이 뒤를 받친다.
     */
    private void clearRingsAfterCommit(Collection<Long> journeyIds) {
        if (journeyIds.isEmpty()) {
            return;
        }
        List<Long> targets = List.copyOf(journeyIds);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            clearRings(targets);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                clearRings(targets);
            }
        });
    }

    private void clearRings(List<Long> journeyIds) {
        int cleared = 0;
        for (Long journeyId : journeyIds) {
            cleared += ringPort.deleteByJourney(journeyId);
        }
        ringMetrics.recordCleared(cleared);
    }

    // ── 계승 ────────────────────────────────────────────────────────────────

    /**
     * 재탐색으로 개정본이 올랐다. <b>구간이 완전히 같은</b> 지하철 단계의 고정만 새 개정본으로
     * 옮긴다.
     *
     * <p>조건을 좁게 잡는 이유는 빠뜨렸을 때 안전한 쪽이 "고정 없음"이기 때문이다 — 새 개정본의
     * 단계에 행이 없으면 아무도 그 열차로 안내하지 않는다. 반대로 조금이라도 다른 구간을
     * 이어받으면 사용자는 <b>엉뚱한 역에서 내린다</b>.
     *
     * <p>{@code PROVISIONAL} 도 옮긴다. 버리면 되살아날 길이 없어서다 — 이동 시작 관측은 여정당
     * 한 번뿐이라 재탐색 뒤에 다시 오지 않고, 정차 관측만으로 열리는 행은 이동 시작 시각이 비어
     * 판정 자체가 불가능하다. 그래서 <b>근거 감사행까지 새 단계로 재발행</b>한다.
     *
     * @param oldLegs 옛 개정본의 단계 전부
     * @param newLegs 새 개정본의 단계 전부(이미 저장돼 id 가 있어야 한다)
     * @return 이어받은 고정 수
     */
    public int inheritOnReroute(List<JourneyLeg> oldLegs, List<JourneyLeg> newLegs) {
        if (oldLegs.isEmpty() || newLegs.isEmpty()) {
            return 0;
        }
        Long journeyId = oldLegs.get(0).getJourneyId();
        int oldRevision = oldLegs.get(0).getRouteRevision();
        List<SubwayLegBinding> bound = bindingRepository.findBoundOfRevision(journeyId, oldRevision);
        if (bound.isEmpty()) {
            return 0;
        }

        Map<Long, JourneyLeg> oldById = new HashMap<>();
        oldLegs.forEach(leg -> oldById.put(leg.getId(), leg));
        Map<Integer, JourneyLeg> newByIndex = new HashMap<>();
        newLegs.forEach(leg -> newByIndex.put(leg.getLegIndex(), leg));

        int inherited = 0;
        for (SubwayLegBinding binding : bound) {
            JourneyLeg oldLeg = oldById.get(binding.getJourneyLegId());
            JourneyLeg newLeg = oldLeg == null ? null : newByIndex.get(oldLeg.getLegIndex());
            if (!sameSubwayRide(oldLeg, newLeg)) {
                continue;
            }
            inherit(binding, oldLeg, newLeg);
            metrics.recordInherited(binding.getStatus(), 1);
            inherited++;
        }
        return inherited;
    }

    /**
     * 두 단계가 <b>같은 열차를 타는 같은 구간</b>인지.
     *
     * <p>노선·방향·승차역·하차역이 하나라도 다르거나 <b>비어 있으면</b> 이어받지 않는다. 모르는
     * 것을 같다고 답하면 그 결과는 엉뚱한 역 하차 안내다.
     */
    private boolean sameSubwayRide(JourneyLeg oldLeg, JourneyLeg newLeg) {
        if (oldLeg == null || newLeg == null) {
            return false;
        }
        if (oldLeg.getType() != JourneyLegType.RIDE_SUBWAY
                || newLeg.getType() != JourneyLegType.RIDE_SUBWAY) {
            return false;
        }
        if (oldLeg.getRouteId() == null || oldLeg.getStartStopId() == null
                || oldLeg.getEndStopId() == null) {
            return false;
        }
        if (!oldLeg.getRouteId().equals(newLeg.getRouteId())
                || !oldLeg.getStartStopId().equals(newLeg.getStartStopId())
                || !oldLeg.getEndStopId().equals(newLeg.getEndStopId())) {
            return false;
        }
        Integer oldWayCode = wayCodeOf(oldLeg);
        return oldWayCode != null && oldWayCode.equals(wayCodeOf(newLeg));
    }

    /**
     * 방향의 원천은 선택된 노선 후보의 {@code wayCode} 다.
     *
     * <p>노선 번호가 같아도 방향이 반대면 완전히 다른 열차다. 여기서 빠뜨리면 상행 고정이 하행
     * 단계로 넘어간다.
     */
    private Integer wayCodeOf(JourneyLeg leg) {
        return Optional.ofNullable(leg.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getWayCode)
                .orElse(null);
    }

    private void inherit(SubwayLegBinding binding, JourneyLeg oldLeg, JourneyLeg newLeg) {
        bindingRepository.save(binding.inheritTo(newLeg));
        if (binding.getStatus() == SubwayBindingStatus.PROVISIONAL) {
            reissueEvidence(binding, newLeg);
        }
        writeInheritAudit(binding, oldLeg, newLeg);
        log.debug("[SubwayBinding] 고정 계승 journeyLegId={} -> {} status={}",
                binding.getJourneyLegId(), newLeg.getId(), binding.getStatus());
    }

    /**
     * 잠정 판정의 <b>근거</b>를 새 단계로 다시 발행한다.
     *
     * <p>행만 옮기면 판정기는 새 단계에서 근거를 하나도 못 읽어 확정으로 못 간다 — 감사행은
     * 단계 번호로 조회되기 때문이다. 옮기는 것은 넷이다.
     *
     * <ul>
     *   <li>지금 세대의 이동 시작 관측 — 후행 확정의 시각 기준</li>
     *   <li>지금 세대의 정차 관측 — 비교할 구간</li>
     *   <li>잠정 판정 한 줄 — 후보 목록의 원본. {@code binding_version} 은 새 행 기준 <b>0</b> 이다</li>
     *   <li>사용자가 거부한 열차 전부 — 세대를 가리지 않는다. 빠뜨리면 방금 거부한 열차를 다시 고른다</li>
     * </ul>
     */
    private void reissueEvidence(SubwayLegBinding binding, JourneyLeg newLeg) {
        List<SubwayBindingEvent> rows = eventRepository.findByJourneyLegIdOrderByIdDesc(
                binding.getJourneyLegId(), Limit.of(AUDIT_SCAN_LIMIT));

        copy(latest(rows, SubwayBindingEventType.MOVEMENT_START,
                binding.getResetGeneration(), binding.getLastMovementSeq()), newLeg);
        copy(latest(rows, SubwayBindingEventType.STATION_STOP_EPISODE,
                binding.getResetGeneration(), binding.getLastEpisodeSeq()), newLeg);
        copy(rows.stream()
                .filter(row -> row.getEventType() == SubwayBindingEventType.PROVISIONAL)
                .filter(row -> row.getResetGeneration() == binding.getResetGeneration())
                .filter(row -> Objects.equals(row.getBindingVersion(), binding.getVersion()))
                .findFirst()
                .orElse(null), newLeg);
        rows.stream()
                .filter(row -> row.getEventType() == SubwayBindingEventType.USER_RESET)
                .forEach(row -> copy(row, newLeg));
    }

    private SubwayBindingEvent latest(List<SubwayBindingEvent> rows, SubwayBindingEventType type,
                                      int resetGeneration, Integer sequence) {
        if (sequence == null) {
            return null;
        }
        return rows.stream()
                .filter(row -> row.getEventType() == type)
                .filter(row -> row.getResetGeneration() == resetGeneration)
                .filter(row -> Objects.equals(row.getEvidenceSequence(), sequence))
                .findFirst()
                .orElse(null);
    }

    /**
     * 감사행 한 줄을 새 단계로 복사한다.
     *
     * <p>{@code binding_version} 을 <b>0</b> 으로 다시 적는다. 새 고정 행은 version 0 에서
     * 시작하는데, 옛 값을 그대로 옮기면 "현재 version 과 일치하는 판단만 읽는다"는 복구 규칙에
     * 걸려 <b>옮기자마자 무효</b>가 된다.
     */
    private void copy(SubwayBindingEvent row, JourneyLeg newLeg) {
        if (row == null) {
            return;
        }
        eventRepository.insertIgnoringDuplicate(
                newLeg.getId(), row.getJourneyId(),
                newLeg.getRouteRevision(), newLeg.getLegIndex(),
                row.getKind().name(), row.getEventType().name(),
                row.getEvidenceSequence(), row.getAlgorithmVersion(),
                0, row.getResetGeneration(),
                row.getOccurredAt(), row.getReceivedAt(),
                row.getChosenTrainNo(), row.getChosenServiceDate(),
                row.getChosenLineId(), row.getChosenDirection(),
                json(row.getDetail()), LocalDateTime.now(clock));
    }

    /** 계승했다는 사실 한 줄. 이 줄이 없으면 새 단계의 고정이 어디서 왔는지 알 수 없다. */
    private void writeInheritAudit(SubwayLegBinding binding, JourneyLeg oldLeg, JourneyLeg newLeg) {
        Instant now = Instant.now(clock);
        ObjectNode detail = objectMapper.createObjectNode();
        detail.put("fromJourneyLegId", binding.getJourneyLegId());
        detail.put("fromRouteRevision", binding.getRouteRevision());
        detail.put("inheritedStatus", binding.getStatus().name());
        detail.put("legIndex", oldLeg.getLegIndex());

        eventRepository.insertIgnoringDuplicate(
                newLeg.getId(), binding.getJourneyId(),
                newLeg.getRouteRevision(), newLeg.getLegIndex(),
                SubwayBindingEventType.INHERITED_ON_REROUTE.kind().name(),
                SubwayBindingEventType.INHERITED_ON_REROUTE.name(),
                null, properties.getAlgorithmVersion(),
                0, binding.getResetGeneration(),
                now, now,
                binding.getTrainNo(), binding.getTrainServiceDate(),
                binding.getTrainLineId(), binding.getTrainDirection(),
                json(detail), LocalDateTime.now(clock));
    }

    /** 직렬화가 실패해도 계승을 막지 않는다 — 근거를 못 적는 것이 고정을 버릴 이유는 아니다. */
    private String json(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[SubwayBinding] 감사행 재발행 직렬화 실패 — {}", e.getMessage());
            return null;
        }
    }
}
