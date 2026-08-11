package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 발송 상태 전이의 <b>트랜잭션 경계를 모두 소유</b>한다.
 *
 * <pre>
 * claimBatch(커밋) → loadTask(재확인) → 발송(트랜잭션 밖) → mark(REQUIRES_NEW)
 * </pre>
 *
 * <p>발송을 트랜잭션 밖에 두는 것이 핵심이다. 안에 넣으면 provider 응답을 기다리는 동안 DB
 * 커넥션을 붙잡는다. 대신 발송 보장이 <b>최소 한 번</b>이 된다 — provider 에 넘긴 직후 죽으면
 * 회수 뒤 다시 간다. 막을 수 없고, 계약으로 받아들인다.
 *
 * <p>mark 가 {@code REQUIRES_NEW} 인 이유는 relay 워커 쪽 예외가 상태 기록까지 되돌리지 않게
 * 하기 위해서다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class JourneyNotificationOutboxStateService {

    private final JourneyNotificationRepository notificationRepository;
    private final JourneySessionRepository sessionRepository;
    private final TransferConnectionRepository transferRepository;
    private final JourneyNotificationProperties properties;
    private final JourneyNotificationMetrics metrics;
    private final Clock clock;

    /** 워커가 죽어 PROCESSING 에 고착된 행을 회수한다. */
    @Transactional
    public void reclaim(Instant now) {
        notificationRepository.reclaimStale(now.minus(properties.claimTimeout()), now);
    }

    /** 배치 claim. 시도 횟수는 이 SQL 이 올린다. */
    @Transactional
    public List<Long> claimBatch(Instant now, int batchSize) {
        List<Long> claimed = notificationRepository.claimBatchForProcessing(
                now, batchSize, LocalDateTime.now(clock));
        metrics.recordClaimed(claimed.size());
        return claimed;
    }

    /**
     * 발송 직전 재확인.
     *
     * <p>claim 과 발송 사이에 상황이 바뀔 수 있다. 여정이 끝났거나, 재탐색으로 경로가 바뀌었거나,
     * 만료 시각을 넘겼거나, 회수됐다가 다시 잡힌 행이 이미 취소돼 있을 수 있다.
     *
     * <p><b>경로 개정본 확인이 여기 있는 이유</b>: "재탐색 뒤 이전 개정본 알림 0"은 enqueue 만으로
     * 보장되지 않는다. 쌓인 뒤에 경로가 바뀌는 창이 남기 때문이다.
     *
     * <p>검사는 싼 것부터 온다. 만료·여정·개정본까지는 이미 읽은 두 행으로 끝나고, lease 와 명령
     * 번호도 같은 {@code journey_session} 행에 있어 조회가 늘지 않는다. 환승 조회만 접근 알림일
     * 때 하나 더 붙는다.
     *
     * @return 보낼 것이 없으면 빈 값. 그때 행은 이미 CANCELED 다
     */
    @Transactional
    public Optional<NotificationSendCommand> loadTask(long notificationId) {
        JourneyNotification n = notificationRepository.findById(notificationId).orElse(null);
        if (n == null || n.getStatus() != JourneyNotificationStatus.PROCESSING) {
            // 회수 뒤 취소된 행이거나 다른 워커가 이미 끝냈다.
            return Optional.empty();
        }

        Instant now = Instant.now(clock);
        if (!n.getExpiresAt().isAfter(now)) {
            cancel(n, "expired");
            return Optional.empty();
        }

        JourneySession journey = sessionRepository.findById(n.getJourneyId()).orElse(null);
        if (journey == null || journey.getStatus() != JourneyStatus.ACTIVE
                || journey.getDeletedAt() != null) {
            cancel(n, "journey-ended");
            return Optional.empty();
        }
        if (journey.getRouteRevision() != n.getRouteRevision()) {
            cancel(n, "route-changed");
            return Optional.empty();
        }
        if (!journey.getGuidanceLeaseUntil().isAfter(now)) {
            // lease 가 끝났다 = 앱이 신호를 끊었다. 화면을 보고 있지 않은 사람에게 보내는 알림은
            // 늦은 알림이고, "lease 만료 이후 발송 0" 이 Phase 5 의 완료 조건이다.
            cancel(n, "lease-expired");
            return Optional.empty();
        }
        if (journey.getCommandVersion() != n.getCommandVersion()) {
            // 사용자 명령(단계 전이·재탐색 수락)이 그 사이에 들어왔다. 이 알림이 근거로 삼은 상황이
            // 더 이상 사실이 아니다.
            //
            // 평가 번호(evaluationVersion)는 <b>일부러</b> 보지 않는다. 그 값은 ETA 숫자가 몇 초
            // 흔들리기만 해도 오르므로(대기 단계는 10초 주기), 여기서 비교하면 정상 알림 대부분이
            // 발송 직전에 죽는다. 조용히 사라지는 알림이 한 번 더 가는 알림보다 나쁘다.
            cancel(n, "command-changed");
            return Optional.empty();
        }
        if (approachLostItsMeaning(n, journey)) {
            return Optional.empty();
        }

        return Optional.of(new NotificationSendCommand(n.getId(), n.getUserId(), n.getJourneyId(),
                n.getRouteRevision(), n.getScopeType(), n.getScopeId(), n.getCheckpoint(),
                n.getChannel(), n.getCommandVersion(), n.getEvaluationVersion(),
                n.getPayload(), n.getExpiresAt()));
    }

    /**
     * 접근 알림이 아직 할 말이 있는가.
     *
     * <p>"갈아탈 차가 곧 온다"는 두 경우에 뜻을 잃는다 — <b>이미 놓쳤을 때</b>(거짓말이 된다)와
     * <b>이미 탔을 때</b>(늦은 알림이다). ④는 아직 발송되지 않은 접근 알림만 취소할 수 있어서,
     * 집어 간 뒤에 상황이 바뀌는 창을 여기로 넘겼다.
     *
     * <p><b>위험 알림에는 걸지 않는다.</b> {@code MISSED} 알림을 환승 상태로 다시 거르면 그 알림이
     * 자기 자신을 지운다 — 가장 중요한 알림이 영영 안 나간다.
     *
     * <p>그래서 조회도 접근 알림일 때만 한다. 모든 행에 걸면 위험 알림 경로에 조회가 하나 붙는다.
     *
     * @return 뜻을 잃었으면 true. 그때 행은 이미 CANCELED 다
     */
    private boolean approachLostItsMeaning(JourneyNotification n, JourneySession journey) {
        if (n.getCheckpoint() != JourneyCheckpoint.TRANSFER_TARGET_APPROACHING) {
            return false;
        }
        TransferConnection transfer = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        n.getJourneyId(), n.getRouteRevision(), n.getScopeId())
                .orElse(null);
        if (transfer == null || transfer.getStatus() == TransferStatus.MISSED) {
            // 근거가 사라졌거나 이미 지나간 차다.
            cancel(n, "transfer-missed");
            return true;
        }
        if (journey.getCurrentLegIndex() > transfer.getToLegIndex()) {
            cancel(n, "already-boarded");
            return true;
        }
        return false;
    }

    private void cancel(JourneyNotification n, String reason) {
        n.markCanceled(reason);
        metrics.recordCanceled(reason);
        log.debug("[JourneyNotificationRelay] id={} 취소({})", n.getId(), reason);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            n.markSent(Instant.now(clock));
            metrics.recordSent(n.getChannel());
        });
    }

    /**
     * 일시 실패. 재시도 예산이 남았고 만료 안에 다시 시도할 수 있으면 되돌리고, 아니면 끝낸다.
     *
     * <p>PROCESSING 이 아닌 행은 건드리지 않는다. 발송 도중 여정이 끝나 이미 취소된 행을
     * 되살리면 죽은 여정의 알림이 나간다. 버스 템플릿에는 이 가드가 없다 — 그쪽은
     * {@code loadTask} 가 취소하면 mark 가 뒤따르지 않아 우연히 안전할 뿐이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransientFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getStatus() != JourneyNotificationStatus.PROCESSING) {
                return;
            }
            Instant nextAttemptAt = Instant.now(clock).plus(properties.backoff());
            if (n.getAttemptCount() >= properties.getMaxAttempts()) {
                n.markDead("max-retry: " + reason);
                metrics.recordFailed(n.getChannel(), "dead");
            } else if (!nextAttemptAt.isBefore(n.getExpiresAt())) {
                // 재시도 시각이 만료를 넘긴다. 보내 봐야 엉뚱한 때에 도착한다.
                n.markCanceled("expired-before-retry");
                metrics.recordCanceled("expired");
            } else {
                n.releaseToPending(reason, nextAttemptAt);
                metrics.recordFailed(n.getChannel(), "transient");
            }
        });
    }

    /** 영구 실패. 재시도하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(long notificationId, String reason) {
        notificationRepository.findById(notificationId).ifPresent(n -> {
            if (n.getStatus() != JourneyNotificationStatus.PROCESSING) {
                return;
            }
            n.markFailed(reason);
            metrics.recordFailed(n.getChannel(), "permanent");
        });
    }
}
