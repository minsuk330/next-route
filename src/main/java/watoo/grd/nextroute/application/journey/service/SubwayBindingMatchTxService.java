package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneySubwayBindingProperties;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingCandidate;
import watoo.grd.nextroute.application.journey.dto.SubwayBindingDecision;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingEventType;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayBindingEventRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * 판정 결과를 반영하는 <b>짧은 트랜잭션</b>.
 *
 * <p>재료를 모으는 일(Redis 조회)과 나뉘어 있는 이유가 이 클래스의 존재 이유다. 잠금을 쥐고 있는
 * 구간이 곧 트랜잭션 길이라, 여기에 외부 호출이 하나라도 들어가면 사용자 요청이 그만큼 막힌다.
 * <b>여기에는 DB 왕복밖에 없어야 한다.</b>
 *
 * <h2>네 걸음</h2>
 * <ol>
 *   <li>세션 행을 {@code FOR SHARE} 로 다시 확인한다 — 재료를 모으는 동안 여정이 끝났거나
 *       재탐색으로 개정본이 올랐을 수 있다. 조건만 다시 읽으면 그 확인과 쓰기 사이가 또 벌어진다</li>
 *   <li>고정 행을 잠근다. <b>잠겨 있으면 건너뛴다</b>(SKIP LOCKED) — 다음 tick 에 다시 온다</li>
 *   <li>CAS 로 쓴다. 잠금은 "누가 먼저인지"를 정하고, CAS 는 "내가 쓴 근거가 그 사이 바뀌었는지"를
 *       본다. 하는 일이 달라 하나를 뺄 수 없다</li>
 *   <li>감사행을 남긴다. <b>CAS 가 0행이면 남기지 않는다</b> — 반영되지 않은 판단을 기록하면
 *       "뒤집힌 결정"이 사실처럼 남는다</li>
 * </ol>
 *
 * <p><b>잠금 순서는 항상 {@code journey_session} → {@code subway_leg_binding} 이다.</b>
 * 종료·재탐색 접점도 세션 행을 먼저 잡으므로 방향이 일치해 역순 대기가 생기지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayBindingMatchTxService {

    private final JourneySessionRepository sessionRepository;
    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayBindingEventRepository eventRepository;
    private final JourneySubwayBindingProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * @param expected 재료를 모으기 <b>전에</b> 읽어 둔 고정 행. CAS 조건이 여기서 나온다
     * @return 실제로 반영됐으면 true
     */
    @Transactional
    public boolean apply(SubwayLegBinding expected, SubwayBindingDecision decision) {
        if (sessionRepository.lockActiveRevisionForShare(
                expected.getJourneyId(), expected.getRouteRevision()).isEmpty()) {
            // 끝난 여정이거나 개정본이 올랐다. 아무것도 하지 않는 것이 맞다.
            return false;
        }
        if (bindingRepository.findForMatch(expected.getJourneyLegId()).isEmpty()) {
            // 지금 누가 그 행을 만지는 중이다. 기다리지 않고 다음 tick 에 다시 온다.
            return false;
        }

        Instant now = Instant.now(clock);
        LocalDateTime auditNow = LocalDateTime.now(clock);
        int rows = decision.kind() == SubwayBindingDecision.Kind.ABANDON
                ? abandon(expected, auditNow)
                : bind(expected, decision, auditNow);
        if (rows == 0) {
            return false;
        }

        writeAudit(expected, decision, now, auditNow);
        return true;
    }

    private int abandon(SubwayLegBinding expected, LocalDateTime auditNow) {
        return bindingRepository.casAbandon(expected.getJourneyLegId(),
                expected.getVersion(), expected.getResetGeneration(),
                expected.getLastMovementSeq(), expected.getLastEpisodeSeq(), auditNow);
    }

    private int bind(SubwayLegBinding expected, SubwayBindingDecision decision,
                     LocalDateTime auditNow) {
        SubwayBindingCandidate chosen = decision.chosen();
        return bindingRepository.casBind(expected.getJourneyLegId(),
                statusOf(decision).name(),
                chosen.trainNo(), chosen.serviceDate(), chosen.lineId(), chosen.direction(),
                expected.getVersion(), expected.getResetGeneration(),
                expected.getLastMovementSeq(), expected.getLastEpisodeSeq(), auditNow);
    }

    /** 교정도 {@code CONFIRMED} 로 끝난다 — 지속 상태에 {@code CORRECTED} 는 없다. */
    private SubwayBindingStatus statusOf(SubwayBindingDecision decision) {
        return decision.isConfirming() ? SubwayBindingStatus.CONFIRMED
                : SubwayBindingStatus.PROVISIONAL;
    }

    /**
     * 무엇을 보고 무엇을 정했는지 한 줄.
     *
     * <p><b>{@code binding_version} 에 {@code expectedVersion + 1} 을 적는다.</b> 네이티브 CAS 는
     * 이미 읽어 둔 엔티티를 고치지 않으므로 눈에 보이는 {@code version} 은 아직 옛 값이다. 그대로
     * 적으면 후보 복구가 "현재 version 과 일치하는 행"만 읽는 규칙에 걸려 <b>쓰자마자 무효</b>가
     * 되고, 후행 확정 경로가 통째로 사라진다. CAS 가 1행을 돌려준 뒤에만 부르므로 이 값이 곧 새
     * version 이다.
     */
    private void writeAudit(SubwayLegBinding expected, SubwayBindingDecision decision,
                            Instant now, LocalDateTime auditNow) {
        SubwayBindingCandidate chosen = decision.chosen();
        SubwayBindingEventType type = eventTypeOf(decision);
        eventRepository.insertIgnoringDuplicate(
                expected.getJourneyLegId(), expected.getJourneyId(),
                expected.getRouteRevision(), expected.getLegIndex(),
                type.kind().name(), type.name(),
                null, properties.getAlgorithmVersion(),
                expected.getVersion() + 1, expected.getResetGeneration(),
                now, now,
                chosen == null ? null : chosen.trainNo(),
                chosen == null ? null : chosen.serviceDate(),
                chosen == null ? null : chosen.lineId(),
                chosen == null ? null : chosen.direction(),
                detailJson(decision), auditNow);
    }

    private SubwayBindingEventType eventTypeOf(SubwayBindingDecision decision) {
        return switch (decision.kind()) {
            case PROVISIONAL -> SubwayBindingEventType.PROVISIONAL;
            case CONFIRMED -> SubwayBindingEventType.CONFIRMED;
            case CORRECTED -> SubwayBindingEventType.CORRECTED;
            case ABANDON -> SubwayBindingEventType.ABANDONED;
            case NONE -> throw new IllegalStateException("판정 없음은 감사행을 남기지 않는다");
        };
    }

    /**
     * 후보 목록의 <b>원본</b>이다. 후행 확정이 여기서 후보를 되찾으므로 모양이 곧 계약이다.
     *
     * <p>직렬화가 실패해도 판정을 막지 않는다 — 근거를 못 적는 것이 반영을 되돌릴 이유는 아니다.
     * 다만 그때는 후보가 비어 확정 경로가 한 번 쉬어 간다.
     */
    private String detailJson(SubwayBindingDecision decision) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("reason", decision.reason());
        ArrayNode candidates = node.putArray("candidates");
        for (SubwayBindingCandidate candidate : decision.candidates()) {
            ObjectNode row = candidates.addObject();
            row.put("trainNo", candidate.trainNo());
            row.put("serviceDate", String.valueOf(candidate.serviceDate()));
            row.put("lineId", candidate.lineId());
            row.put("direction", candidate.direction());
            row.put("departureFrom", String.valueOf(candidate.departureFrom()));
            row.put("departureTo", String.valueOf(candidate.departureTo()));
            row.put("quality", candidate.quality() == null ? null : candidate.quality().name());
            row.put("terminalId", candidate.terminalId());
            row.put("express", candidate.express() == null ? null : candidate.express().name());
            row.put("gapSeconds", candidate.gapSeconds());
        }
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[SubwayBinding] 판단 근거 직렬화 실패 — {}", e.getMessage());
            return "{}";
        }
    }
}
