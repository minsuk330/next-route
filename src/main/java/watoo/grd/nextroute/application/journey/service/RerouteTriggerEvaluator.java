package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties;
import watoo.grd.nextroute.application.journey.dto.RerouteTriggerSignal;
import watoo.grd.nextroute.application.journey.dto.TransferAssessmentBatch;
import watoo.grd.nextroute.application.journey.observability.JourneyRerouteMetrics;
import watoo.grd.nextroute.application.journey.port.out.RerouteCooldownPort;
import watoo.grd.nextroute.application.journey.service.JourneyEvaluationContextLoader.JourneyEvaluationContext;

import java.time.Instant;
import java.util.List;

/**
 * 방아쇠를 세고 기록한다. 알림이 꺼져 있으면 <b>여기서 끝난다</b>(shadow).
 *
 * <p>~~{@code RerouteProposalService} 를 주입받지 않는 것이 그 계약을 구조로 보장한다~~ —
 * <b>PR-3 에서 뒤집혔다.</b> 알릴 통로(⑥)가 생겼으므로 이제 신호는 {@link RerouteAlertPublisher}
 * 로 넘어가고, 그쪽이 "깨울 만한가"를 판단해 제안을 만들고 push 한 줄을 쌓는다. 이 클래스가
 * 여전히 세기만 하는 것은 맞다 — 발행 여부는 {@code journey.reroute.alert.enabled}(기본 꺼짐)가
 * 정하고, 꺼져 있으면 예전과 똑같이 shadow 다.
 *
 * <p>계약은 이렇게 굳었다: <b>알림은 서버가 먼저, 경로 변경은 사용자 선택만. 자동 적용 없음.</b>
 *
 * <p>shadow 인 이유가 이 클래스의 존재 이유다. 재탐색은 외부 API 를 부르고 요금이 붙는데, 하루에
 * 몇 번 도는지 모르는 채로 외부 검색을 붙이면 <b>요금이 먼저 온다.</b> 먼저 발생률을 재고 그
 * 수를 보고 켠다.
 *
 * <p>cooldown 은 {@link RerouteCooldownPort.Scope#TRIGGER} 키를 쓴다. 사용자 요청과 같은 키를
 * 쓰면 아무것도 만들지 않는 이 셈이 잠금을 태워 사용자 요청이 2분간 거절된다(D6).
 *
 * <p>부르는 자리는 {@code JourneyEvaluationService} 의 <b>소유권 확인 뒤·트랜잭션 밖</b>이다.
 * 앞에 두면 lease 를 잃은 워커가 cooldown 을 태워 이긴 워커의 방아쇠를 먹고, 트랜잭션 안에 두면
 * Redis 호출이 DB 커넥션을 붙잡는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RerouteTriggerEvaluator {

    private final RerouteTriggerPolicy policy;
    private final RerouteAlertPublisher alertPublisher;
    private final RerouteCooldownPort cooldownPort;
    private final JourneyRerouteMetrics metrics;
    private final JourneyRerouteProperties properties;
    private final RerouteRefreshSignal rerouteRefreshSignal;

    /**
     * 이번 평가에서 재탐색을 찾아볼 만한 사건이 있었는지 보고 세어 둔다.
     *
     * <p>{@code context.previousAssessments()} 를 직전 상태로 쓴다. 저장소를 다시 읽지 않는
     * 것이 중요하다 — 이 시점에는 {@code TransferAssessmentWriter} 가 이미 덮어썼으므로 다시
     * 읽으면 직전이 아니라 <b>방금 쓴 값</b>을 보게 된다.
     */
    public void evaluate(JourneyEvaluationContext context, TransferAssessmentBatch batch,
                         Instant now) {
        if (!properties.getTrigger().isEnabled() || batch.isEmpty()) {
            return;
        }

        List<RerouteTriggerSignal> signals = policy.signals(
                context.journeyId(), context.routeRevision(), context.previousAssessments(),
                batch.assessments(), context.currentLegIndex(), now);

        for (RerouteTriggerSignal signal : signals) {
            // cooldown 은 신호가 있을 때만 묻는다. 대부분의 평가에는 신호가 없으므로, 매번
            // 물으면 초당 200 평가가 그대로 Redis 왕복이 된다.
            metrics.recordTriggerFired(signal.reason().tag());
            if (!cooldownPort.tryAdmit(signal.journeyId(), signal.routeRevision(),
                    RerouteCooldownPort.Scope.TRIGGER)) {
                continue;
            }
            metrics.recordTriggerAdmitted(signal.reason().tag());
            record(signal);
            if (properties.getBoard().isEnabled()) {
                // 보드 모드(#264): 사건이 제안을 직접 만들지 않는다. 보드를 바로 다시 찾게 하고(최소
                // 간격 우회), push 판단은 보드 갱신 뒤에 한다(PR-5). 여기서 v1 제안을 심으면 보드를 밀어낸다.
                rerouteRefreshSignal.requestUrgent(signal.journeyId());
                continue;
            }
            // 알림 스위치가 꺼져 있으면 여기서 끝난다(shadow 그대로). 켜져 있으면 발행 경로가
            // 스스로 한 번 더 걸러 낸다 — 이 셈은 "찾아볼 만한가"까지고, "깨울 만한가"는
            // 발행 경로의 판단이다.
            alertPublisher.publish(signal, context.userId(), now);
        }
    }

    /**
     * 통과한 신호만 남긴다.
     *
     * <p>켜진 것마다 남기면 cooldown 이 깎는 만큼이 전부 로그가 되어 초당 200 평가에서 시끄럽다.
     * 개수는 지표가 세고, 로그는 "실제로 찾아볼 만했던 순간"의 맥락을 남기는 몫이다.
     */
    private void record(RerouteTriggerSignal signal) {
        log.info("[JourneyReroute] trigger journeyId={} rev={} transferIndex={} reason={} {}->{}",
                signal.journeyId(), signal.routeRevision(), signal.transferIndex(),
                signal.reason().tag(), signal.previousStatus(), signal.currentStatus());
    }
}
