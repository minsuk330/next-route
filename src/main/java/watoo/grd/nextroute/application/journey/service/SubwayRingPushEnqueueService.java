package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayRingPushReason;
import watoo.grd.nextroute.application.journey.dto.SubwayRingState;
import watoo.grd.nextroute.application.journey.observability.SubwayRingMetrics;
import watoo.grd.nextroute.application.subway.service.SubwayLineIdMapper;
import watoo.grd.nextroute.domain.journey.entity.JourneyLeg;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegRouteOption;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushEvent;
import watoo.grd.nextroute.domain.journey.repository.JourneyLegRouteOptionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayRingPushOutboxRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

/**
 * 원격 갱신을 대기열에 넣는다.
 *
 * <h2>링 저장과 트랜잭션을 나눈다</h2>
 *
 * <p>링은 Redis 에 latest-wins 로 덮어써도 되지만 push 는 DB 행이다. 둘을 한 트랜잭션에 묶으면
 * 대기열 삽입이 실패했을 때 링 갱신까지 되돌아가고, 그러면 다음 tick 이 같은 계산을 처음부터
 * 다시 한다 — 링은 이미 옳게 계산됐는데 push 쪽 사정으로 버리는 셈이다.
 *
 * <h2>넣기 전에 옛 대기 행을 민다</h2>
 *
 * <p>부분 unique 가 있어 건너뛰면 삽입이 충돌한다. 그리고 충돌하지 않더라도 대기 중인 옛 화면을
 * 그대로 두면 순서가 뒤집혔을 때 그것이 새 화면을 덮는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayRingPushEnqueueService {

    private final SubwayRingPushOutboxRepository outboxRepository;
    private final JourneyLegRouteOptionRepository routeOptionRepository;
    private final SubwayRingPushDecider decider;
    private final SubwayRingPushPayloadFactory payloadFactory;
    private final SubwayRingMetrics metrics;
    private final JourneySubwayRingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 링이 바뀌었으면 한 건 넣는다.
     *
     * <p>{@code REQUIRES_NEW} 가 아니다. 부르는 쪽(갱신 tick)은 트랜잭션 밖이라 여기서 새로
     * 열린다.
     *
     * @param ride 표시용 노선 이름을 얻을 단계
     */
    @Transactional
    public void enqueueIfChanged(SubwayRingState previous, SubwayRingState current,
                                 JourneyLeg ride) {
        if (!properties.getPush().isEnabled()) {
            return;
        }
        Optional<SubwayRingPushDecider.Decision> decided = decider.decide(previous, current);
        if (decided.isEmpty()) {
            return;
        }
        SubwayRingPushDecider.Decision decision = decided.get();
        if (isSameScreenAsLast(current, decision)) {
            // 같은 화면을 두 번 만들지 않는다. 링은 5초마다 도는데 대부분의 tick 은 화면을 안 바꾼다.
            metrics.recordPushSkippedEnqueue("same_signature");
            return;
        }
        ObjectNode contentState = payloadFactory.contentState(
                current.journeyId(), lineNameOf(ride, current), current, decision.updatedAt());

        insert(current.journeyId(), current.routeRevision(), current.legIndex(),
                current.bindingVersion(), decision.reason(), decision.signature(),
                SubwayRingPushEvent.UPDATE, contentState);
    }

    /**
     * 여정이 끝났다. 잠금화면을 지우라고 보낸다.
     *
     * <p><b>DB 로 보장한다.</b> 앱이 꺼져 있어도 나가야 하므로 Redis 에 두면 안 된다 — 캐시가
     * 날아가면 잠금화면에 죽은 안내가 영원히 남는다.
     *
     * <p><b>부르는 쪽 트랜잭션에 올라탄다.</b> {@code REQUIRES_NEW} 로 열면 여정 종료가
     * 되돌아갔을 때 종료 프레임만 나가, 살아 있는 여정의 잠금화면이 지워진다. 링 삭제를
     * {@code afterCommit} 으로 미룬 것과 방향이 다른 이유는 여기 쓰는 것이 <b>Redis 가 아니라
     * 같은 DB 행</b>이라, 함께 커밋되거나 함께 되돌아갈 수 있기 때문이다.
     *
     * <p>{@code afterCommit} 으로 미루지 않는 이유도 같다. 커밋과 콜백 사이에 프로세스가 죽으면
     * 그 종료는 <b>영영 만들어지지 않는다</b> — outbox 를 쓰는 목적이 정확히 그 창을 없애는 것이다.
     *
     * <p><b>여정마다 부르지 않는다.</b> 부르는 자리 중 하나가 lease 만료 배치인데, 그 트랜잭션은
     * 문장 하나만 감싸도록 설계돼 있다 — 여정 수만큼 왕복을 끼우면 만료 하나가 커넥션 풀을
     * 마르게 한다. 그래서 밀기·넣기 각각 한 문장이다.
     *
     * @return 넣은 종료 행 수. 보낸 적이 없는 여정은 지울 화면도 없어 세지 않는다
     */
    @Transactional
    public int enqueueTerminal(Collection<Long> journeyIds) {
        if (!properties.getPush().isEnabled() || journeyIds.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now(clock);
        LocalDateTime auditNow = LocalDateTime.now(clock);

        // 대기 중인 진행 갱신을 먼저 민다. 종료는 밀지 않는다 — 접기 경로가 둘 이상 지나가도
        // 그 여정이 잠금화면을 지울 기회를 잃으면 안 된다.
        outboxRepository.skipOpenUpdatesByJourneyIn(journeyIds, auditNow);

        int inserted = outboxRepository.enqueueTerminalByJourneyIn(journeyIds,
                now.getEpochSecond(), now.toEpochMilli(), decider.expiresAt(now), auditNow);
        if (inserted > 0) {
            metrics.recordPushEnqueued(SubwayRingPushReason.END, true);
        }
        return inserted;
    }

    // ── 넣기 ────────────────────────────────────────────────────────────────

    private boolean insert(Long journeyId, int routeRevision, int legIndex, int bindingVersion,
                           SubwayRingPushReason reason, String signature,
                           SubwayRingPushEvent event, ObjectNode contentState) {
        Instant now = Instant.now(clock);
        LocalDateTime auditNow = LocalDateTime.now(clock);
        outboxRepository.skipOpen(journeyId, routeRevision, legIndex, auditNow);
        try {
            int inserted = outboxRepository.enqueue(journeyId, routeRevision, legIndex,
                    bindingVersion, now.getEpochSecond(), signature, reason.isCritical(),
                    reason.tag(), event.wireValue(), json(contentState),
                    decider.expiresAt(now), auditNow);
            metrics.recordPushEnqueued(reason, inserted > 0);
            return inserted > 0;
        } catch (DataIntegrityViolationException e) {
            // 두 워커가 같은 여정을 동시에 계산했다. 누가 더 새 걸 넣었다는 뜻이라 정상 결말이다.
            metrics.recordPushEnqueued(reason, false);
            log.debug("[SubwayRingPush] 대기열 삽입 경합 journeyId={} reason={}", journeyId, reason);
            return false;
        }
    }

    private boolean isSameScreenAsLast(SubwayRingState current,
                                       SubwayRingPushDecider.Decision decision) {
        return outboxRepository.findLatestSignature(current.journeyId(), current.routeRevision(),
                        current.legIndex())
                .filter(signature -> signature.equals(decision.signature()))
                .isPresent();
    }

    /**
     * 표시용 노선 이름.
     *
     * <p>{@code props.routeName} 은 앱에서 non-optional 이라 비면 디코딩이 통째로 실패한다.
     * 그래서 세 겹으로 받친다 — 선택된 노선 후보의 이름 → 내부 line_id 역매핑 → line_id 원값.
     */
    private String lineNameOf(JourneyLeg ride, SubwayRingState ring) {
        return Optional.ofNullable(ride.getSelectedRouteOptionId())
                .flatMap(routeOptionRepository::findById)
                .map(JourneyLegRouteOption::getRouteName)
                .filter(name -> name != null && !name.isBlank())
                .or(() -> SubwayLineIdMapper.toLineName(ride.getRouteId()))
                .orElseGet(() -> ride.getRouteId() == null
                        ? "지하철"
                        : ride.getRouteId());
    }

    private String json(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("Live Activity content-state 직렬화 실패", e);
        }
    }
}
