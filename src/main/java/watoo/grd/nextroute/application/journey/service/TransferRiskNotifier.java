package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.JourneyEvaluationOutcome;
import watoo.grd.nextroute.application.journey.dto.NotificationContext;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * "이번 평가에서 알릴 일이 생겼는가"를 판단하고 outbox 에 쌓는다.
 *
 * <p>{@code JourneyEvaluationScheduleWriter.finish} 안에서, 판정 저장 <b>뒤</b>·탑승 예측 저장
 * <b>앞</b>에 불린다. 같은 트랜잭션이라 "알렸다" 마커와 알림 행이 함께 커밋된다 — 둘이 갈라지면
 * 알림은 갔는데 마커가 없어 10초 뒤 또 가거나, 마커만 있고 알림이 없어 영영 안 간다.
 *
 * <h2>규칙</h2>
 * <ol>
 *   <li><b>나빠질 때만 알린다.</b> 같은 상태로 10초마다 다시 알리면 사용자는 같은 이야기를
 *       반복해 듣는다</li>
 *   <li><b>알릴 상태인지를 먼저 거른다.</b> 순위표에서 {@code UNKNOWN}(2)이
 *       {@code CATCHABLE}(1)보다 나쁘므로, 숫자만 비교하면 "모르겠다"가 알림이 된다</li>
 *   <li><b>좋아지면 쌓인 알림을 밀어내고 마커를 비운다.</b> 안 하면 옛 알림이 최대 30분 뒤에
 *       나가고, 다시 나빠져도 "이미 알렸다"에 걸려 안 나간다</li>
 *   <li><b>{@code UNKNOWN} 에서는 마커를 비우지 않는다.</b> 잠깐 모르는 것과 실제로 좋아진 것은
 *       다르다</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TransferRiskNotifier {

    /** 알림을 만드는 상태. 이 필터가 순위 비교보다 <b>먼저</b> 와야 한다(규칙 2). */
    private static final Set<TransferStatus> NOTIFIABLE =
            EnumSet.of(TransferStatus.AT_RISK, TransferStatus.MISSED);

    /** 마커가 없을 때의 순위. 어떤 실제 상태보다도 작아야 첫 알림이 나간다. */
    private static final int NO_MARKER = -1;

    /**
     * 회복이 지울 수 있는 체크포인트.
     *
     * <p>접근 알림({@code TRANSFER_TARGET_APPROACHING})은 여기 없다. 여유가 생긴 것과 "갈아탈
     * 차가 곧 온다"는 서로 다른 이야기라, 상태가 좋아졌다고 그것까지 지우면 정작 사용자가 고개를
     * 들어야 하는 순간의 알림이 사라진다.
     */
    private static final Set<JourneyCheckpoint> RISK_CHECKPOINTS =
            EnumSet.of(JourneyCheckpoint.TRANSFER_RISK_AT_RISK, JourneyCheckpoint.TRANSFER_RISK_MISSED);

    private final TransferConnectionRepository transferRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final JourneyNotificationEnqueuer enqueuer;
    private final JourneyNotificationProperties properties;

    /**
     * 나빠진 환승마다 알림을 쌓는다.
     *
     * <p><b>반드시 소유권 확인(CAS) 뒤에 불러야 한다.</b> 순서를 바꾸면 lease 를 잃은 워커가
     * 마커를 앞당겨 찍어, 이긴 워커의 알림을 영구히 막는다.
     *
     * <p>relay 를 깨우는 이벤트는 여기서 발행하지 않는다. 같은 평가에서 접근 알림
     * ({@link TransferTargetApproachNotifier})도 쌓일 수 있어, 각자 발행하면 평가 한 번에 이벤트가
     * 둘 나간다. 부르는 쪽({@code JourneyEvaluationScheduleWriter})이 둘의 결과를 합쳐 한 번만
     * 발행한다.
     *
     * @return 이 평가에서 알림을 쌓으려 시도했으면 true
     */
    public boolean notifyWorsened(JourneyEvaluationOutcome outcome, NotificationContext context) {
        if (!properties.isEnqueueEnabled()) {
            return false;
        }
        if (context == null || context.channels().isEmpty()) {
            // 보낼 길이 없다. 여기서 마커를 쓰면 그 환승은 영영 알림이 없다.
            return false;
        }

        // 마커를 쓸 엔티티를 다시 조회한다. 같은 트랜잭션이라 1차 캐시가 판정 저장이 방금 만진
        // 것과 같은 인스턴스를 준다. TransferAssessmentWriter 의 시그니처를 바꿔 관리 중인
        // 엔티티를 내보내면, 조회 경로(트랜잭션이 반환 시점에 끝난다)에서 마커가 조용히 사라진다.
        Map<Integer, TransferConnection> byIndex = transferRepository
                .findByJourneyIdAndRouteRevisionAndDeletedAtIsNullOrderByTransferIndex(
                        outcome.journeyId(), outcome.routeRevision())
                .stream()
                .collect(Collectors.toMap(TransferConnection::getTransferIndex, Function.identity()));

        boolean enqueuedAny = false;
        for (TransferRiskAssessment assessment : outcome.batch().assessments()) {
            TransferConnection transfer = byIndex.get(assessment.transferIndex());
            if (transfer == null) {
                continue;
            }
            if (!assessment.isUpcoming(context.currentLegIndex())) {
                // 이미 지나온 환승이다. 알려 봐야 할 수 있는 일이 없다.
                continue;
            }
            enqueuedAny |= handle(outcome, context, assessment, transfer);
        }

        return enqueuedAny;
    }

    /** @return 이 환승에서 알림을 쌓으려 시도했으면 true */
    private boolean handle(JourneyEvaluationOutcome outcome, NotificationContext context,
                           TransferRiskAssessment assessment, TransferConnection transfer) {
        if (!NOTIFIABLE.contains(assessment.status())) {
            recoverIfImproved(outcome, assessment, transfer);
            return false;
        }

        int previous = severityOfMarker(transfer.getLastNotifiedStatus());
        if (assessment.status().severity() <= previous) {
            // 같거나 나아졌다. 나빠질 때만 알린다.
            return false;
        }

        List<String> enqueuedKeys = new ArrayList<>(context.channels().size());
        for (NotificationChannel channel : context.channels()) {
            enqueuedKeys.add(enqueuer.enqueueTransferRisk(
                    outcome.journeyId(), outcome.routeRevision(), assessment, channel));
        }
        // 승격(AT_RISK→MISSED)으로 남은 옛 행을 밀어낸다. 두 상태는 별개 행이라 재시도로 순서가
        // 뒤집히면 사용자가 "놓쳤다" 다음에 "놓칠 것 같다"를 본다.
        notificationRepository.supersedePendingByScope(
                outcome.journeyId(), outcome.routeRevision(),
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(), enqueuedKeys);

        // 행이 실제로 생겼는지와 무관하게 마커를 쓴다. 중복이라 0행인 것은 "사용자가 이미 봤다"는
        // 뜻이고, 그때 마커를 안 쓰면 10초마다 영원히 헛삽입한다.
        transfer.markNotified(assessment.status().name());
        return true;
    }

    /**
     * 상태가 알릴 값이 아닌 값으로 좋아졌을 때의 회복 경로.
     *
     * <p>{@code UNKNOWN} 에서는 아무것도 하지 않는다 — 잠깐 근거를 못 구한 것과 실제로 여유가
     * 생긴 것은 다르다. 여기서 마커를 비우면 provider 가 한 번 흔들릴 때마다 재알림이 나간다.
     */
    private void recoverIfImproved(JourneyEvaluationOutcome outcome,
                                   TransferRiskAssessment assessment,
                                   TransferConnection transfer) {
        if (assessment.status() == TransferStatus.UNKNOWN) {
            return;
        }
        if (transfer.getLastNotifiedStatus() == null) {
            return;
        }
        notificationRepository.supersedeAllPendingByScope(
                outcome.journeyId(), outcome.routeRevision(),
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(), RISK_CHECKPOINTS);
        transfer.clearNotified();
    }

    /** 마커에 적힌 상태의 순위. 값이 없거나 모르는 값이면 어떤 상태보다도 낮게 본다. */
    private int severityOfMarker(String lastNotifiedStatus) {
        if (lastNotifiedStatus == null) {
            return NO_MARKER;
        }
        try {
            return TransferStatus.valueOf(lastNotifiedStatus).severity();
        } catch (IllegalArgumentException e) {
            // 값 목록이 바뀐 뒤 남은 옛 마커. 알림을 막는 쪽보다 한 번 더 알리는 쪽이 낫다.
            log.warn("[JourneyNotification] 모르는 마커 값={}", lastNotifiedStatus);
            return NO_MARKER;
        }
    }
}
