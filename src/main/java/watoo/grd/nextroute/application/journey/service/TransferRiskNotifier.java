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

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
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

    /** native 취소 쿼리에 넘기는 같은 목록. */
    private static final List<String> RISK_CHECKPOINT_NAMES =
            RISK_CHECKPOINTS.stream().map(Enum::name).toList();

    private final TransferConnectionRepository transferRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final JourneyNotificationEnqueuer enqueuer;
    private final JourneyNotificationProperties properties;
    private final watoo.grd.nextroute.application.journey.config.JourneyRerouteProperties rerouteProperties;
    private final RouteAlertBudget alertBudget;

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

        if (alertBudget.enabled()) {
            return notifyWorsenedWithBudget(outcome, context, byIndex);
        }

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

    private boolean boardMode() {
        return rerouteProperties.isEnabled() && rerouteProperties.getBoard().isEnabled()
                && rerouteProperties.getAlert().isEnabled();
    }

    /** @return 이 환승에서 알림을 쌓으려 시도했으면 true */
    private boolean handle(JourneyEvaluationOutcome outcome, NotificationContext context,
                           TransferRiskAssessment assessment, TransferConnection transfer) {
        if (!NOTIFIABLE.contains(assessment.status())) {
            recoverIfImproved(outcome, assessment, transfer);
            return false;
        }
        if (yieldsToApproach(assessment, context.currentLegIndex())) {
            return false;
        }

        int previous = severityOfMarker(transfer.getLastNotifiedStatus());
        if (assessment.status().severity() <= previous) {
            // 같거나 나아졌다. 나빠질 때만 알린다.
            return false;
        }

        List<String> enqueuedKeys = new ArrayList<>(context.channels().size());
        for (NotificationChannel channel : context.channels()) {
            enqueuedKeys.add(boardMode()
                    // 보드 모드(#264 PR-5): 대안이 있으면 재탐색 push 하나로 합친다. 그 판단이 끝날
                    // 시간을 벌려고 늦춰 넣고, 재탐색 push 가 나가면 이 행이 취소된다.
                    ? enqueuer.enqueueTransferRisk(outcome.journeyId(), outcome.routeRevision(),
                            assessment, channel, java.time.Duration.ofSeconds(
                                    rerouteProperties.getBoard().getRiskMergeDelaySeconds()))
                    : enqueuer.enqueueTransferRisk(
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
     * 이 판정은 접근 알림에 자리를 넘기는가.
     *
     * <p>사용자가 <b>이미 그 환승 정류장에 서 있고</b> 상태가 {@code AT_RISK} 면 넘긴다.
     *
     * <p>그 자리에서 여유는 {@code 다음 차 도착 - 지금} 이라, 차가 2분 안에 오면 상태는 거의 항상
     * {@code AT_RISK} 다. 그런데 위험 알림의 문구는 "지금 이동하면 다음 차를 탈 수 있어요"이고,
     * <b>이미 정류장에 서 있는 사람에게 이동하라는 것은 틀린 지시</b>다. 그 순간 쓸모 있는 말은
     * "갈아탈 차가 곧 도착해요" 하나뿐이므로 {@link TransferTargetApproachNotifier} 가 맡는다.
     *
     * <p>{@code MISSED} 는 넘기지 않는다. 기다리던 차를 놓쳤다는 사실은 정류장에 서 있는
     * 사람에게도 — 오히려 그 사람에게 가장 — 필요하다.
     *
     * <p>마커도 쓰지 않고 넘긴다. 나중에 {@code MISSED} 로 떨어지면 그때는 알려야 하는데, 여기서
     * 마커를 찍어 두면 순위 비교가 그 알림을 막을 이유가 없어도 규칙이 한 겹 복잡해진다.
     */
    private boolean yieldsToApproach(TransferRiskAssessment assessment, int currentLegIndex) {
        return assessment.status() == TransferStatus.AT_RISK
                && currentLegIndex >= assessment.toLegIndex();
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

    // ── 피로 정책(#428 PR-1b) ─────────────────────────────────────────────────────
    //
    // 꺼져 있으면 위의 handle/recoverIfImproved 가 그대로 돈다. 켜져 있을 때만 아래가 돈다 — 두 길을 한 메서드에
    // 섞으면 "꺼짐 = 정책 이전과 같은 DB 쓰기"를 눈으로 확인하기 어렵다.

    /**
     * 피로 정책이 켜졌을 때의 평가 한 번.
     *
     * <p>회복을 먼저 돌린다. 한 환승의 회복이 울릴 권리를 돌려놓으면 같은 평가에서 나빠진 다른 환승이 그 권리를
     * 쓸 수 있다.
     *
     * <p>나빠진 환승은 <b>심한 순서</b>(같으면 앞 환승)로 처리한다. 권리는 먼저 시도한 쪽이 가져가므로, 순서가
     * 곧 "평가 한 번에 여러 환승이 나빠지면 가장 심한 하나만 울린다"를 만든다.
     */
    private boolean notifyWorsenedWithBudget(JourneyEvaluationOutcome outcome, NotificationContext context,
                                             Map<Integer, TransferConnection> byIndex) {
        List<TransferRiskAssessment> worsened = new ArrayList<>();
        for (TransferRiskAssessment assessment : outcome.batch().assessments()) {
            TransferConnection transfer = byIndex.get(assessment.transferIndex());
            if (transfer == null || !assessment.isUpcoming(context.currentLegIndex())) {
                continue;
            }
            if (!NOTIFIABLE.contains(assessment.status())) {
                recoverWithBudget(outcome, assessment, transfer);
                continue;
            }
            if (yieldsToApproach(assessment, context.currentLegIndex())) {
                continue;
            }
            if (assessment.status().severity() <= severityOfMarker(transfer.getLastNotifiedStatus())) {
                continue;
            }
            worsened.add(assessment);
        }
        worsened.sort(Comparator
                .comparingInt((TransferRiskAssessment a) -> a.status().severity()).reversed()
                .thenComparingInt(TransferRiskAssessment::transferIndex));

        boolean enqueuedAny = false;
        for (TransferRiskAssessment assessment : worsened) {
            enqueuedAny |= handleWithBudget(outcome, context, assessment, byIndex.get(assessment.transferIndex()));
        }
        return enqueuedAny;
    }

    /**
     * 나빠진 환승 하나. 순서 = 대체(취소) → 판정 → 삽입.
     *
     * <p>대체를 먼저 하는 이유: 아직 안 나간 울림 알림(빠듯)이 권리를 쥔 채 남아 있으면 새 알림(놓침)은 조용해지고,
     * 그 뒤 옛 알림이 취소돼 울림이 0번이 된다. 먼저 취소하고 그 키를 판정에 넘기면 새 알림이 권리를 물려받는다.
     *
     * @return 이 환승에서 알림을 쌓으려 시도했으면 true
     */
    private boolean handleWithBudget(JourneyEvaluationOutcome outcome, NotificationContext context,
                                     TransferRiskAssessment assessment, TransferConnection transfer) {
        long journeyId = outcome.journeyId();
        int revision = outcome.routeRevision();
        String logicalKey = JourneyNotificationKeys.logical(journeyId, revision,
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(),
                JourneyCheckpoint.ofTransferStatus(assessment.status()));

        // 승격(빠듯→놓침)으로 남은 옛 위험 알림을 밀어낸다. 접근 알림은 여기서 건드리지 않는다 — 울림일 때만
        // 아래에서 따로 취소한다.
        List<String> canceled = notificationRepository.cancelPendingByScopeReturningKeys(
                journeyId, revision, JourneyNotificationScope.TRANSFER.name(), assessment.transferIndex(),
                RISK_CHECKPOINT_NAMES, logicalKey, "superseded");

        AlertDelivery delivery = alertBudget.decide(journeyId, revision, logicalKey, canceled);

        if (delivery == AlertDelivery.QUIET && alertBudget.claimHeldByReroute(journeyId, revision)) {
            // 이미 울린 것이 재탐색 제안이다. 조용한 위험 알림으로 그 할 일을 덮지 않는다. 마커는 쓴다 — 안 쓰면
            // 10초마다 같은 판단을 되풀이한다.
            alertBudget.recordQuietDominated();
            log.debug("[JourneyNotification] quiet-dominated journeyId={} transfer={}",
                    journeyId, assessment.transferIndex());
            transfer.markNotified(assessment.status().name());
            return false;
        }

        // 합치기 창은 울림에만 건다. 조용한 갱신은 늦출 이유가 없다 — 이미 울린 알림을 고쳐 쓸 뿐이다.
        Duration hold = delivery == AlertDelivery.ALERTING
                ? alertBudget.holdFor(assessment, boardMode())
                : Duration.ZERO;
        boolean created = false;
        for (NotificationChannel channel : context.channels()) {
            created |= enqueuer.enqueueTransferRisk(journeyId, revision, assessment, channel, hold, delivery)
                    .created();
        }

        if (delivery == AlertDelivery.ALERTING) {
            // 울리는 위험 알림이 나가는 마당에 뒤늦게 도착하는 "곧 온다"는 거짓 안심이다(정책 이전과 같은 규칙).
            // 조용한 알림은 접근 알림을 지우지 않는다 — 소리 없는 갱신이 정작 고개를 들어야 할 알림을 없애면 안 된다.
            notificationRepository.supersedeAllPendingByScope(journeyId, revision,
                    JourneyNotificationScope.TRANSFER, assessment.transferIndex(),
                    EnumSet.of(JourneyCheckpoint.TRANSFER_TARGET_APPROACHING));
            if (!created) {
                // 중복이라 행이 안 생겼다. 울릴 알림이 없는데 권리를 쥐고 있으면 이 경로 버전은 다시 못 울린다.
                alertBudget.release(journeyId, logicalKey);
            }
        }

        transfer.markNotified(assessment.status().name());
        return true;
    }

    /**
     * 피로 정책의 회복 경로. 위험 알림을 거두는 것은 {@link #recoverIfImproved} 와 같고, 거둔 알림이 울릴 권리를
     * 쥐고 있었으면 돌려놓는다 — 그 알림은 나가지 않았으니 사용자는 아직 한 번도 울림을 받지 않았다.
     */
    private void recoverWithBudget(JourneyEvaluationOutcome outcome, TransferRiskAssessment assessment,
                                   TransferConnection transfer) {
        if (assessment.status() == TransferStatus.UNKNOWN || transfer.getLastNotifiedStatus() == null) {
            return;
        }
        List<String> canceled = notificationRepository.cancelPendingByScopeReturningKeys(
                outcome.journeyId(), outcome.routeRevision(), JourneyNotificationScope.TRANSFER.name(),
                assessment.transferIndex(), RISK_CHECKPOINT_NAMES, "", "superseded");
        alertBudget.release(outcome.journeyId(), canceled, 0L);
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
