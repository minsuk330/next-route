package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Clock;
import java.time.Instant;

/**
 * "갈아탈 차가 곧 온다"를 outbox 에 쌓는다(N2).
 *
 * <p>{@link TransferRiskNotifier} 와 같은 트랜잭션·같은 훅에서 <b>그 뒤에</b> 불린다. 위험 알림과
 * 클래스를 나눈 이유는 취향이 아니라 <b>의존성이 실제로 다르기</b> 때문이다. 이쪽은
 * {@code TransferConnection} 마커도, 상태 서열 비교도, 밀어내기도 쓰지 않는다.
 *
 * <h2>위험 알림과 다른 세 가지</h2>
 * <ol>
 *   <li><b>마커가 없다.</b> 중복은 {@code deliveryKey} 의 unique 가 막는다. 임박 창이 열린 동안
 *       판정은 매 평가마다 참이라 헛삽입이 몇 번 나지만, 행은 늘지 않는다. 마커 칸을 새로 만들면
 *       마이그레이션이 따라온다</li>
 *   <li><b>밀어내기를 하지 않는다.</b> 접근 알림은 자기 만료(다음 차 도착 시각)로만 사라진다</li>
 *   <li><b>"쌓았다"의 뜻이 다르다.</b> 위험 알림은 시도만으로 참이지만 여기서는 <b>행이 실제로
 *       생겼을 때만</b> 참이다 — 그 값이 relay 를 깨울지의 기준이라, 시도를 기준으로 삼으면
 *       창이 열려 있는 내내 아무것도 안 늘어난 채 relay 만 계속 깨운다</li>
 * </ol>
 *
 * <p>예외를 삼키지 않는 자세는 위험 알림과 같다. 이미 rollback-only 가 된 트랜잭션에서 나는
 * 예외를 삼키면 시끄러운 실패가 조용한 실패로 바뀔 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferTargetApproachNotifier {

    private final JourneyNotificationEnqueuer enqueuer;
    private final TransferTargetApproachPolicy policy;
    private final JourneyNotificationProperties properties;
    private final Clock clock;

    /**
     * 임박한 환승마다 접근 알림을 쌓는다.
     *
     * <p><b>반드시 소유권 확인(CAS) 뒤에 불러야 한다.</b> 진 워커가 남긴 알림이 나가면 안 된다.
     *
     * @return 접근 알림 행이 <b>실제로 생겼으면</b> true
     */
    public boolean notifyApproach(JourneyEvaluationOutcome outcome, NotificationContext context) {
        if (!properties.isEnqueueEnabled()) {
            return false;
        }
        if (context == null || context.channels().isEmpty()) {
            // 보낼 길이 없다. 마커를 쓰지 않으므로 다음 평가가 같은 조건을 다시 만난다.
            return false;
        }

        // 평가 한 번의 기준 시각을 여기서 한 번만 읽는다. 판정과 만료가 다른 시각을 보면
        // 조건을 통과한 알림이 삽입 시점에 이미 만료일 수 있다.
        Instant now = Instant.now(clock);

        boolean created = false;
        for (TransferRiskAssessment assessment : outcome.batch().assessments()) {
            if (!policy.isApproaching(assessment, context.currentLegIndex(), now)) {
                continue;
            }
            for (NotificationChannel channel : context.channels()) {
                created |= enqueuer.enqueueTargetApproach(
                        outcome.journeyId(), outcome.routeRevision(), assessment, channel, now);
            }
        }
        return created;
    }
}
