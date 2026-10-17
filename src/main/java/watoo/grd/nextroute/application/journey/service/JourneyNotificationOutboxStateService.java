package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties.RetryPolicy;
import watoo.grd.nextroute.application.journey.dto.ClaimedNotification;
import watoo.grd.nextroute.application.journey.dto.NotificationSendCommand;
import watoo.grd.nextroute.application.journey.dto.SendTask;
import watoo.grd.nextroute.application.journey.exception.StaleClaimException;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import com.fasterxml.jackson.databind.JsonNode;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotification;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationStatus;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.entity.TransferConnection;
import watoo.grd.nextroute.domain.journey.entity.TransferStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.journey.repository.TransferConnectionRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
 *
 * <p><b>증서(#451).</b> 상태 변경은 전부 {@code WHERE id AND status='PROCESSING' AND attempt_count = 증서} 인 네이티브
 * UPDATE 다. 0행이면 {@link StaleClaimException} 을 던져, 같은 트랜잭션에서 먼저 한 울릴 권리 반납까지 되돌린다. 권리
 * 반납이 먼저인 순서(여정 → 알림 잠금)는 평가기와 같아서 바꾸지 않는다.
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
    /** 재시도 간격·시도 상한 정책(#451 PR-3). FIXED 면 옛 {@code backoff-seconds}·{@code max-attempts} 그대로. */
    private final JourneyRetryBackoff retryBackoff;

    /**
     * 워커가 죽어 PROCESSING 에 고착된 행을 회수한다. 기준은 DB {@code now()} − 회수 기준이다.
     *
     * @param now 회수된 행이 다시 집힐 시각(claim 과 같은 앱 시계)
     * @return 회수한 행 수
     */
    @Transactional
    public int reclaim(Instant now) {
        int reclaimed = notificationRepository.reclaimStale(
                properties.claimTimeout().toMillis() / 1000.0, now, LocalDateTime.now(clock));
        metrics.recordReclaimed(reclaimed);
        return reclaimed;
    }

    /**
     * heartbeat 뒤 회수, 한 트랜잭션(#451). heartbeat 가 먼저라 이 인스턴스가 들고 있는 행은 같은 주기의 회수에 걸리지
     * 않는다.
     *
     * @param inFlight     이 인스턴스가 들고 있는 행 → 증서
     * @param reclaimAllowed 직전 heartbeat 성공이 충분히 최근일 때만 true(DB 가 멈췄다 돌아온 직후 남의 행을 쓸어 담지 않게)
     * @return 회수한 행 수(회수를 안 했으면 0)
     */
    @Transactional
    public int heartbeatAndReclaim(Map<Long, Integer> inFlight, boolean reclaimAllowed, Instant now) {
        if (!inFlight.isEmpty()) {
            List<Map.Entry<Long, Integer>> entries = List.copyOf(inFlight.entrySet());
            String ids = entries.stream().map(e -> String.valueOf(e.getKey()))
                    .collect(Collectors.joining(",", "{", "}"));
            String tokens = entries.stream().map(e -> String.valueOf(e.getValue()))
                    .collect(Collectors.joining(",", "{", "}"));
            notificationRepository.heartbeat(ids, tokens);
        }
        return reclaimAllowed ? reclaim(now) : 0;
    }

    /**
     * 배치 claim. 시도 횟수는 이 SQL 이 올리고, 올린 값이 증서다. 순서는 {@code relay.claim-order}(#451 PR-2) —
     * 기본 SCHEDULED 는 옛 SQL 그대로, PRIORITY_FIRST 는 우선순위 먼저(오래 기다린 행은 긴급과 동급).
     */
    @Transactional
    public List<ClaimedNotification> claimBatch(Instant now, int batchSize) {
        LocalDateTime auditNow = LocalDateTime.now(clock);
        JourneyNotificationProperties.Relay relay = properties.getRelay();
        List<JourneyNotificationRepository.ClaimRow> rows =
                relay.getClaimOrder() == JourneyNotificationProperties.ClaimOrder.PRIORITY_FIRST
                        ? notificationRepository.claimBatchForProcessingPriorityFirst(
                                now, auditNow.minusSeconds(relay.getAgingSeconds()), batchSize, auditNow)
                        : notificationRepository.claimBatchForProcessing(now, batchSize, auditNow);
        List<ClaimedNotification> claimed = rows.stream()
                .map(row -> new ClaimedNotification(row.getId(), row.getToken()))
                .toList();
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
     * <p>알림 행과 여정 행은 한 문장으로 읽는다(#461 후속 — 발송 용량의 병목이 건당 커넥션 왕복이었다).
     *
     * @return 보낼 것이 없으면 빈 값. 그때 행은 이미 CANCELED 이거나 남의 것이다
     * @throws StaleClaimException 취소하려는데 증서가 맞지 않았다(취소·권리 반납 모두 되돌려짐)
     */
    @Transactional
    public Optional<NotificationSendCommand> loadTask(ClaimedNotification claim) {
        return loadSendTask(claim).map(SendTask::command);
    }

    /**
     * {@link #loadTask} 와 같은 재확인. 접수 기록({@link #markSent(ClaimedNotification, SendTask)})이 행을 다시 읽지 않게
     * 적재 시각을 같이 돌려준다.
     */
    @Transactional
    public Optional<SendTask> loadSendTask(ClaimedNotification claim) {
        Object[] row = notificationRepository.findWithJourney(claim.id()).stream().findFirst().orElse(null);
        JourneyNotification n = row == null ? null : (JourneyNotification) row[0];
        if (n == null || n.getStatus() != JourneyNotificationStatus.PROCESSING
                || n.getAttemptCount() != claim.token()) {
            // 회수 뒤 취소된 행이거나, 다른 워커가 이미 끝냈거나, 회수돼 남이 다시 잡았다.
            metrics.recordStaleWrite("load");
            return Optional.empty();
        }
        Instant now = Instant.now(clock);
        if (!n.getExpiresAt().isAfter(now)) {
            cancel(n, claim.token(), "expired", true);
            return Optional.empty();
        }

        int token = claim.token();
        JourneySession journey = (JourneySession) row[1];
        if (journey == null || journey.getStatus() != JourneyStatus.ACTIVE
                || journey.getDeletedAt() != null) {
            cancel(n, token, "journey-ended", false);
            return Optional.empty();
        }
        if (journey.getRouteRevision() != n.getRouteRevision()) {
            cancel(n, token, "route-changed", false);
            return Optional.empty();
        }
        if (!journey.getGuidanceLeaseUntil().isAfter(now)) {
            // lease 가 끝났다 = 앱이 신호를 끊었다. 화면을 보고 있지 않은 사람에게 보내는 알림은
            // 늦은 알림이고, "lease 만료 이후 발송 0" 이 Phase 5 의 완료 조건이다.
            cancel(n, token, "lease-expired", false);
            return Optional.empty();
        }
        if (journey.getCommandVersion() != n.getCommandVersion()) {
            // 사용자 명령(단계 전이·재탐색 수락)이 그 사이에 들어왔다. 이 알림이 근거로 삼은 상황이
            // 더 이상 사실이 아니다.
            //
            // 평가 번호(evaluationVersion)는 <b>일부러</b> 보지 않는다. 그 값은 ETA 숫자가 몇 초
            // 흔들리기만 해도 오르므로(대기 단계는 10초 주기), 여기서 비교하면 정상 알림 대부분이
            // 발송 직전에 죽는다. 조용히 사라지는 알림이 한 번 더 가는 알림보다 나쁘다.
            cancel(n, token, "command-changed", false);
            return Optional.empty();
        }
        if (approachLostItsMeaning(n, token, journey)) {
            return Optional.empty();
        }

        return Optional.of(new SendTask(new NotificationSendCommand(n.getId(), n.getUserId(), n.getJourneyId(),
                n.getRouteRevision(), n.getScopeType(), n.getScopeId(), n.getCheckpoint(),
                n.getChannel(), n.getCommandVersion(), n.getEvaluationVersion(),
                n.getPayload(), n.getExpiresAt()), n.getCreatedAt()));
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
    private boolean approachLostItsMeaning(JourneyNotification n, int token, JourneySession journey) {
        // 접근 알림의 의미 검사는 환승 scope에만 적용한다. 관리자 테스트처럼 실제
        // TransferConnection을 가리키지 않는 JOURNEY scope 행을 transfer index 0으로
        // 잘못 해석하면, 테스트 행이 발송 직전에 조용히 취소된다.
        if (n.getCheckpoint() != JourneyCheckpoint.TRANSFER_TARGET_APPROACHING
                || n.getScopeType() != JourneyNotificationScope.TRANSFER) {
            return false;
        }
        TransferConnection transfer = transferRepository
                .findByJourneyIdAndRouteRevisionAndTransferIndexAndDeletedAtIsNull(
                        n.getJourneyId(), n.getRouteRevision(), n.getScopeId())
                .orElse(null);
        if (transfer == null || transfer.getStatus() == TransferStatus.MISSED) {
            // 근거가 사라졌거나 이미 지나간 차다.
            cancel(n, token, "transfer-missed", false);
            return true;
        }
        if (journey.getCurrentLegIndex() > transfer.getToLegIndex()) {
            cancel(n, token, "already-boarded", false);
            return true;
        }
        return false;
    }

    /**
     * 증서 조건부 취소. 권리 반납이 먼저(여정 → 알림 잠금 순서), 그 다음 알림 행. 알림 행이 0행이면 던져서 반납까지 되돌린다.
     * 지표는 둘 다 성공한 뒤에 센다.
     *
     * @param lost 보냈어야 했는데 끝난 행인가(#446 "빠짐없이" — 만료만 해당)
     */
    private void cancel(JourneyNotification n, int token, String reason, boolean lost) {
        boolean released = releaseAlertClaim(n);
        if (notificationRepository.finishIfClaimed(n.getId(), token, "CANCELED", truncate(reason),
                LocalDateTime.now(clock)) != 1) {
            throw new StaleClaimException("cancel", n.getId(), token);
        }
        if (lost) {
            recordLost(n, reason);
        }
        recordReleased(released);
        metrics.recordCanceled(reason);
        log.debug("[JourneyNotificationRelay] id={} 취소({})", n.getId(), reason);
    }

    /**
     * 접수 기록. 지표(#448 e2e·잃음·만료 뒤 발송)는 바꾸기 전에 읽은 행으로 잰다 — 조건부 UPDATE 는 엔티티를 고치지 않는다.
     *
     * @throws StaleClaimException 증서가 맞지 않았다. 행은 남의 것이라 그대로 둔다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(ClaimedNotification claim) {
        notificationRepository.findById(claim.id()).ifPresent(n -> markSent(claim, new SendTask(
                new NotificationSendCommand(n.getId(), n.getUserId(), n.getJourneyId(), n.getRouteRevision(),
                        n.getScopeType(), n.getScopeId(), n.getCheckpoint(), n.getChannel(), n.getCommandVersion(),
                        n.getEvaluationVersion(), n.getPayload(), n.getExpiresAt()),
                n.getCreatedAt())));
    }

    /**
     * 접수 기록, 행을 다시 읽지 않는 쪽(#461 후속). 증서 조건부 UPDATE 한 문장이고, 지표는 재확인 때 읽은 값으로 잰다.
     *
     * @throws StaleClaimException 증서가 맞지 않았다. 행은 남의 것이라 그대로 둔다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(ClaimedNotification claim, SendTask task) {
        NotificationSendCommand c = task.command();
        Instant now = Instant.now(clock);
        int token = writeToken(claim, "sent");
        if (notificationRepository.markSentIfClaimed(claim.id(), token, now,
                LocalDateTime.now(clock)) != 1) {
            throw new StaleClaimException("sent", claim.id(), claim.token());
        }
        metrics.recordSent(c.channel());
        if (task.createdAt() != null) {
            // created_at 은 시간대 없는 칼럼이고 적재 때 같은 clock 으로 찍었다
            // (JourneyNotificationEnqueuer). 그래서 그 clock 의 시간대로 되돌려야 맞는다.
            // DB now() 와 섞어 빼면 두 시계 차이가 지연에 섞인다.
            Instant createdAt = task.createdAt().atZone(clock.getZone()).toInstant();
            metrics.recordDeliveryLag(c.channel(), Duration.between(createdAt, now));
        }
        recordEndToEnd(c.checkpoint(), c.payload(), c.expiresAt(), now);
    }

    /**
     * 처음 본 시각 → 접수(#446). 처음 본 시각은 위험 알림 payload 에 적재 때 실려 왔다({@code JourneyNotificationEnqueuer}).
     * 없으면 못 잰 것으로 센다. 만료를 넘겨 접수됐는지도 여기서 본다 — 발송 직전 재확인 뒤 공급자 왕복이 길면 생길 수 있다.
     */
    private void recordEndToEnd(JourneyCheckpoint checkpoint, JsonNode payload, Instant expiresAt, Instant sentAt) {
        if (expiresAt != null && sentAt.isAfter(expiresAt)) {
            metrics.recordSentAfterExpiry(checkpoint);
        }
        Instant firstSeenAt = payloadInstant(payload, JourneyNotificationEnqueuer.FIRST_SEEN_AT);
        if (firstSeenAt == null) {
            metrics.recordE2eUnmeasured(checkpoint);
            return;
        }
        String delivery = payloadText(payload, "delivery", "none");
        // 보류는 적재 때 payload 에 적어 둔 값이다 — 재시도가 scheduled_at 을 백오프 시각으로 미뤄 되읽을 수 없다.
        String holdMs = payloadText(payload, JourneyNotificationEnqueuer.HOLD_MS, null);
        if (holdMs != null) {
            try {
                metrics.recordHold(checkpoint, delivery, Duration.ofMillis(Long.parseLong(holdMs)));
            } catch (NumberFormatException ignored) {
                // 깨진 값은 보류를 못 잰 것으로 둔다.
            }
        }
        metrics.recordE2e(payloadText(payload, JourneyNotificationEnqueuer.SEEN_TIER, "UNKNOWN"), checkpoint,
                delivery, payloadText(payload, JourneyNotificationEnqueuer.SEEN_SOURCE, "unknown"),
                Duration.between(firstSeenAt, sentAt));
    }

    /**
     * 보냈어야 했는데 끝난 행을 센다(#446 "빠짐없이"). 상태를 바꾸기 전에 부른다 — 행 내용만 읽는다.
     */
    private void recordLost(JourneyNotification n, String reason) {
        metrics.recordLost(n.getCheckpoint(), deliveryOf(n), reason);
    }

    /** payload 의 울림 종류. 없으면(피로 정책 꺼짐·정책 이전 행) {@code none} — 발송기는 그때 울림으로 보낸다. */
    static String deliveryOf(JourneyNotification n) {
        return payloadText(n.getPayload(), "delivery", "none");
    }

    private static String payloadText(JsonNode payload, String field, String fallback) {
        if (payload == null) {
            return fallback;
        }
        JsonNode node = payload.get(field);
        return node == null || node.isNull() ? fallback : node.asText(fallback);
    }

    private static Instant payloadInstant(JsonNode payload, String field) {
        String text = payloadText(payload, field, null);
        if (text == null) {
            return null;
        }
        try {
            return Instant.parse(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** {@code fcm: UNREGISTERED} → {@code failed:UNREGISTERED}. 지표 tag 라 코드만 남긴다. */
    private static String failedReason(String reason) {
        if (reason == null) {
            return "failed:unknown";
        }
        String code = reason.startsWith("fcm: ") ? reason.substring(5) : reason;
        int space = code.indexOf(' ');
        return "failed:" + (space > 0 ? code.substring(0, space) : code);
    }

    /**
     * 일시 실패. 재시도 예산이 남았고 만료 안에 다시 시도할 수 있으면 되돌리고, 아니면 끝낸다.
     *
     * <p>PROCESSING 이 아닌 행은 건드리지 않는다. 발송 도중 여정이 끝나 이미 취소된 행을
     * 되살리면 죽은 여정의 알림이 나간다. 버스 템플릿에는 이 가드가 없다 — 그쪽은
     * {@code loadTask} 가 취소하면 mark 가 뒤따르지 않아 우연히 안전할 뿐이다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markTransientFailure(ClaimedNotification claim, String reason) {
        notificationRepository.findById(claim.id()).ifPresent(n -> {
            int token = writeToken(claim, "transient");
            if (n.getStatus() != JourneyNotificationStatus.PROCESSING || n.getAttemptCount() != token) {
                throw new StaleClaimException("transient", n.getId(), token);
            }
            int attempts = sendAttempts(n);
            if (attempts >= retryBackoff.maxAttempts()) {
                boolean released = releaseAlertClaim(n);
                finish(n, token, "DEAD", "max-retry: " + reason, "transient");
                recordLost(n, "dead");
                recordReleased(released);
                metrics.recordFailed(n.getChannel(), "dead");
                return;
            }
            Instant now = Instant.now(clock);
            Instant nextAttemptAt = retryAt(now, retryBackoff.delayAfter(attempts), n.getExpiresAt());
            if (!nextAttemptAt.isBefore(n.getExpiresAt())) {
                // 재시도 시각이 만료를 넘긴다. 보내 봐야 엉뚱한 때에 도착한다.
                boolean released = releaseAlertClaim(n);
                finish(n, token, "CANCELED", "expired-before-retry", "transient");
                recordLost(n, "expired-before-retry");
                recordReleased(released);
                metrics.recordCanceled("expired");
            } else {
                if (notificationRepository.releaseToPendingIfClaimed(n.getId(), token, truncate(reason),
                        nextAttemptAt, LocalDateTime.now(clock)) != 1) {
                    throw new StaleClaimException("transient", n.getId(), token);
                }
                metrics.recordFailed(n.getChannel(), "transient");
            }
        });
    }

    /**
     * 다음 시도 시각(#451 후속). EXPONENTIAL 에서 정상 간격이 만료를 넘으면 마지막 한 번을 {@code 만료 − FCM HTTP 최대 수명}
     * 으로 당긴다 — 장애가 끝난 뒤 수명이 남았는데 버리던 행(③c' 150초 장애 42건)을 살린다. 당긴 시각도 지금 + 첫 간격보다
     * 이르면(앞 시도가 SDK 안에서 아직 살아 있을 수 있다) 당기지 않고 그대로 돌려줘 호출자가 취소한다. FIXED 는 그대로다.
     */
    Instant retryAt(Instant now, Duration delay, Instant expiresAt) {
        Instant normal = now.plus(delay);
        if (normal.isBefore(expiresAt) || properties.getRetryPolicy() != RetryPolicy.EXPONENTIAL) {
            return normal;
        }
        Instant last = expiresAt.minus(properties.getFcm().httpMaxLifetime());
        return last.isBefore(now.plus(properties.retryInitial())) ? normal : last;
    }

    /**
     * 실제 발송 시도 수(#451). claim 이 올린 {@code attempt_count} 에서 회수로 다시 나간 몫({@code requeue_count})을 뺀다 —
     * 워커가 죽어 회수된 것은 공급자에게 거절당한 시도가 아니다.
     */
    static int sendAttempts(JourneyNotification n) {
        return n.getAttemptCount() - n.getRequeueCount();
    }

    private void finish(JourneyNotification n, int token, String status, String reason, String op) {
        if (notificationRepository.finishIfClaimed(n.getId(), token, status, truncate(reason),
                LocalDateTime.now(clock)) != 1) {
            throw new StaleClaimException(op, n.getId(), token);
        }
    }

    /**
     * 기록에 쓸 증서. 평소에는 claim 이 받은 시도 번호 그대로다. 부하 실험의 증서 끔 칸(#464,
     * {@code relay.loadtest-unfenced})에서는 지금 DB 의 시도 번호를 쓴다 — 회수 뒤 다른 워커가 다시 집은 행에도 옛 시도의
     * 기록이 들어간다. 그렇게 넘어간 수를 따로 센다.
     */
    private int writeToken(ClaimedNotification claim, String op) {
        if (!properties.getRelay().isLoadtestUnfenced()) {
            return claim.token();
        }
        Integer current = notificationRepository.findAttemptCount(claim.id());
        if (current == null) {
            return claim.token();
        }
        if (current != claim.token()) {
            metrics.recordLoadtestUnfencedLateWrite(op);
            // 실험 분석기(relay_fault.py)가 이 id 의 뒤이은 접수를 따라가 추가 발송인지 가른다
            log.warn("[JourneyNotificationOutbox] id={} 증서 끔 늦은 기록 통과({}) claim={} current={}",
                    claim.id(), op, claim.token(), current);
        }
        return current;
    }

    /** {@code last_failure_reason} 는 varchar(500). 예상 못 한 예외 문구가 길어 기록 자체가 실패하면 안 된다. */
    private static String truncate(String reason) {
        return reason == null || reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    /**
     * 발송기가 보내지 않기로 했다(#428 조용한 갱신을 못 받는 기기 등). 실패가 아니라 취소다.
     *
     * <p>PROCESSING 이 아닌 행은 건드리지 않는다 — {@link #markTransientFailure} 와 같은 이유.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSkipped(ClaimedNotification claim, String reason) {
        notificationRepository.findById(claim.id()).ifPresent(n -> {
            int token = writeToken(claim, "skipped");
            if (n.getStatus() != JourneyNotificationStatus.PROCESSING || n.getAttemptCount() != token) {
                throw new StaleClaimException("skipped", n.getId(), claim.token());
            }
            try {
                cancel(n, token, reason, false);
            } catch (StaleClaimException e) {
                throw new StaleClaimException("skipped", n.getId(), claim.token());
            }
        });
    }

    /** 영구 실패. 재시도하지 않는다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentFailure(ClaimedNotification claim, String reason) {
        notificationRepository.findById(claim.id()).ifPresent(n -> {
            int token = writeToken(claim, "permanent");
            if (n.getStatus() != JourneyNotificationStatus.PROCESSING || n.getAttemptCount() != token) {
                throw new StaleClaimException("permanent", n.getId(), claim.token());
            }
            boolean released = releaseAlertClaim(n);
            finish(n, token, "FAILED", reason, "permanent");
            recordLost(n, failedReason(reason));
            recordReleased(released);
            metrics.recordFailed(n.getChannel(), "permanent");
        });
    }

    /**
     * 돌려받기(#428 PR-1b). 이 행이 울릴 권리를 쥔 알림이었는데 SENT 가 아닌 끝 상태로 가면 권리를 비운다 — 안
     * 비우면 그 경로 버전의 사용자는 울림을 한 번도 못 받는다. 권리를 쥔 키가 아니면 조건에 걸려 아무 일도 없다.
     *
     * <p><b>상태를 바꾸기 전에</b> 부른다. 이 UPDATE 가 여정 행을 잠그는데, 알림 행 변경이 먼저 flush 되면 잠금
     * 순서가 알림 → 여정이 돼 평가기(여정 → 알림)와 반대가 된다. 같은 이유로 이 행 자신은 아직 살아 있는 상태로
     * 보이므로 id 로 뺀다.
     *
     * <p>피로 정책이 꺼져 있으면 부르지 않는다 — 꺼짐은 정책 이전과 같은 DB 쓰기다. 저장소 SQL 로 한꺼번에 취소하는
     * 대체(superseded*)는 여기를 지나지 않는다. 그쪽은 대체한 알림이 권리를 물려받는다.
     */
    private boolean releaseAlertClaim(JourneyNotification n) {
        if (!properties.getFatigue().isEnabled()) {
            return false;
        }
        return sessionRepository.releaseAlertClaim(n.getJourneyId(), List.of(n.getLogicalCheckpointKey()),
                n.getId()) == 1;
    }

    /** 반납 지표는 알림 행 변경까지 성공한 뒤에 센다 — 늦은 기록이면 반납이 롤백된다. */
    private void recordReleased(boolean released) {
        if (released) {
            metrics.recordAlertClaim("released");
        }
    }
}
