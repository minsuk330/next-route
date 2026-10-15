package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 경로 버전마다 한 번뿐인 "울릴 권리"를 나눠 준다(#428 PR-1b).
 *
 * <p>비유: 한 경로 버전에 어깨를 두드릴 수 있는 표가 한 장 있다. 먼저 표를 딴 알림만 소리를 내고, 나머지는
 * 조용히 메모를 바꿔 끼운다. 표를 쥔 알림이 아직 안 나간 채 더 나쁜 알림으로 대체되면 표는 대체한 쪽으로
 * 넘어가고(물려받기), 표를 쥔 알림이 만료·실패로 끝내 안 나가면 표는 다시 걸린다(돌려받기).
 *
 * <p>표는 {@code journey_session} 행의 두 칸(V69)이다. 알림 행 개수를 세지 않는 이유 — 위험 알림(평가기)·재탐색
 * 알림·보드 push 가 서로 다른 트랜잭션에서 동시에 돌아, 세는 방식은 둘 다 "0건이니 울림"을 본다. 한 행 UPDATE
 * 는 DB 가 하나만 이기게 해 준다.
 *
 * <p><b>부르는 쪽의 트랜잭션 안에서 돈다.</b> 판정과 알림 행 삽입이 같이 커밋돼야 한다 — 갈라지면 표만 쥐고
 * 알림은 없는 상태(영영 안 울림)나, 알림은 있는데 표가 없는 상태(두 번 울림)가 생긴다.
 *
 * <p>피로 정책이 꺼져 있으면 아무도 이 빈을 부르지 않는다({@link #enabled()} 로 부르는 쪽이 가른다).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RouteAlertBudget {

    private final JourneySessionRepository sessionRepository;
    private final JourneyNotificationProperties properties;
    private final JourneyNotificationMetrics metrics;
    private final Clock clock;

    public boolean enabled() {
        return properties.getFatigue().isEnabled();
    }

    /**
     * 여정 행을 잠근다. 보드 push·재탐색 알림이 <b>가장 먼저</b> 부른다.
     *
     * <p>평가기는 {@code finishEvaluation} 이 이미 이 행을 잠그고 들어온다. 다른 경로도 같은 행부터 잡아야 잠금
     * 순서가 journey_session → reroute_proposal → journey_notification 하나로 맞는다.
     */
    public void lockJourney(long journeyId) {
        sessionRepository.lockForAlert(journeyId);
    }

    /**
     * 이 알림이 울리는가.
     *
     * <p>순서가 중요하다: 부르는 쪽이 <b>대체(취소)를 먼저</b> 하고 그 결과를 {@code canceledKeys} 로 넘긴다.
     * 판정을 먼저 하면 아직 안 나간 울림 알림이 표를 쥔 채로 남아 새 알림은 조용해지고, 곧이어 옛 알림은
     * 취소된다 — 울림이 0번이 된다.
     *
     * @param newLogicalKey 이번에 만들 알림의 논리 키(채널을 뺀 키). 채널이 여럿이어도 판정은 한 번이다
     * @param canceledKeys  이번 트랜잭션이 실제로 PENDING → CANCELED 로 옮긴 행의 논리 키
     */
    public AlertDelivery decide(long journeyId, int routeRevision, String newLogicalKey,
                                Collection<String> canceledKeys) {
        Set<String> from = distinct(canceledKeys);
        if (!from.isEmpty()
                && sessionRepository.inheritAlertClaim(journeyId, routeRevision, from, newLogicalKey) == 1) {
            metrics.recordAlertClaim("inherited");
            return AlertDelivery.ALERTING;
        }
        if (sessionRepository.tryClaimAlert(journeyId, routeRevision, newLogicalKey) == 1) {
            metrics.recordAlertClaim("won");
            return AlertDelivery.ALERTING;
        }
        metrics.recordAlertClaim("quiet");
        return AlertDelivery.QUIET;
    }

    /**
     * 이미 울린(또는 울릴) 알림이 재탐색 제안인가.
     *
     * <p>그렇다면 조용한 위험 알림은 보내지 않는다 — 같은 갱신 열쇠로 덮으면 "이렇게 가면 더 빨라요"라는 할 일이
     * "환승이 위험해요"라는 상태 보고로 바뀐다. 더 쓸모 있는 알림을 덜 쓸모 있는 알림이 덮으면 안 된다.
     */
    public boolean claimHeldByReroute(long journeyId, int routeRevision) {
        return sessionRepository.findAlertClaimKey(journeyId, routeRevision)
                .map(JourneyNotificationKeys::checkpointOf)
                .filter(checkpoint -> checkpoint == JourneyCheckpoint.REROUTE_SUGGESTED)
                .isPresent();
    }

    /**
     * 권리를 돌려놓는다. 지금 권리를 쥔 키가 {@code logicalKeys} 중 하나이고, 그 키의 다른 채널 행이 살아 있거나
     * 나가지 않았을 때만 실제로 비운다 — 아니면 아무 일도 없다.
     *
     * @param exceptNotificationId 지금 끝내는 행(아직 상태 변경이 반영되기 전). 없으면 0
     */
    public void release(long journeyId, Collection<String> logicalKeys, long exceptNotificationId) {
        Set<String> keys = distinct(logicalKeys);
        if (keys.isEmpty()) {
            return;
        }
        if (sessionRepository.releaseAlertClaim(journeyId, keys, exceptNotificationId) == 1) {
            metrics.recordAlertClaim("released");
            log.debug("[JourneyNotification] 울릴 권리 반납 journeyId={} keys={}", journeyId, keys);
        }
    }

    public void release(long journeyId, String logicalKey) {
        release(journeyId, List.of(logicalKey), 0L);
    }

    /** 재탐색이 이미 울려 조용한 위험 알림을 보내지 않았다. */
    public void recordQuietDominated() {
        metrics.recordAlertClaim("quiet-dominated");
    }

    /**
     * 울림 위험 알림의 합치기 창(보류). 이 시간만큼 발송을 미뤄 그 사이 더 나쁜 소식이나 대안이 오면 대체해
     * 한 번에 보낸다.
     *
     * <pre>
     * 보류 = 수집 주기 × N + 여유
     *   N = 1  빠듯, 또는 보드 모드가 아닌 놓침
     *   N = 2  보드 모드의 놓침 — 대안 계산(보드 갱신)이 끝나기를 기다린다
     * 빠듯이면 보류 ≤ 다음 차 도착 − 지금 − 행동 여유. 0 이하면 즉시(0)
     * </pre>
     *
     * <p>상한을 빠듯에만 거는 이유: 놓침의 다음 차 도착 시각은 <b>이미 놓친 차</b>의 시각이라(대개 과거) 행동
     * 마감이 아니다. 거기에 상한을 걸면 놓침은 언제나 즉시가 돼 합치기 창이 사라진다.
     */
    public Duration holdFor(TransferRiskAssessment assessment, boolean boardMode) {
        JourneyNotificationProperties.Fatigue fatigue = properties.getFatigue();
        int rounds = assessment.status() == TransferStatus.MISSED && boardMode ? 2 : 1;
        long hold = fatigue.getHoldRoundSeconds() * rounds + fatigue.getHoldMarginSeconds();
        if (assessment.status() == TransferStatus.AT_RISK && assessment.nextBusArrivalAt() != null) {
            long cap = Duration.between(Instant.now(clock), assessment.nextBusArrivalAt()).getSeconds()
                    - fatigue.getHoldActionMarginSeconds();
            if (cap <= 0) {
                return Duration.ZERO;
            }
            hold = Math.min(hold, cap);
        }
        return Duration.ofSeconds(hold);
    }

    private static Set<String> distinct(Collection<String> keys) {
        return keys == null ? Set.of() : new LinkedHashSet<>(keys);
    }
}
