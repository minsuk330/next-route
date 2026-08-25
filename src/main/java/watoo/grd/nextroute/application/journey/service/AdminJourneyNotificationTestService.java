package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.JourneySession;
import watoo.grd.nextroute.domain.journey.entity.JourneyStatus;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.journey.repository.JourneySessionRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;
import watoo.grd.nextroute.global.error.BusinessException;
import watoo.grd.nextroute.global.error.code.JourneyErrorCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 관리자용 여정 알림 테스트 enqueue.
 *
 * <p>Firebase를 API 컨테이너에서 직접 호출하지 않는다. 테스트 행을 기존
 * {@code journey_notification} outbox에 넣고, worker의 relay가 운영 알림과 같은
 * 토큰 선택·FCM 발송 경로를 처리하게 한다. 따라서 Firebase 자격증명은 기존처럼 worker에만
 * 남는다.
 */
@Service
@RequiredArgsConstructor
public class AdminJourneyNotificationTestService {

    private final JourneySessionRepository sessionRepository;
    private final JourneyNotificationRepository notificationRepository;
    private final JourneyNotificationProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 활성 Journey 하나에 테스트 알림을 큐에 넣는다.
     *
     * <p>테스트 요청마다 UUID를 키에 넣어 같은 checkpoint를 여러 번 시험할 수 있다. 일반
     * checkpoint 멱등 키를 재사용하면 두 번째 시험이 중복으로 무시된다.
     */
    @Transactional
    public QueuedTestNotification enqueue(long journeyId, JourneyCheckpoint checkpoint) {
        JourneySession journey = sessionRepository.findById(journeyId)
                .filter(found -> found.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(JourneyErrorCode.JOURNEY_NOT_FOUND));
        if (journey.getStatus() != JourneyStatus.ACTIVE
                || !journey.getGuidanceLeaseUntil().isAfter(Instant.now(clock))) {
            throw new BusinessException(JourneyErrorCode.JOURNEY_NOT_ACTIVE);
        }

        Instant now = Instant.now(clock);
        Instant expiresAt = now.plusSeconds(properties.getDefaultTtlSeconds());
        String testId = UUID.randomUUID().toString();
        String logicalKey = "ADMIN_TEST:" + journeyId + ":" + testId;
        String deliveryKey = logicalKey + ":PUSH";
        String payload = payload(testId, checkpoint);

        List<Long> created = notificationRepository.enqueue(
                journeyId, journey.getRouteRevision(),
                JourneyNotificationScope.JOURNEY.name(), 0,
                checkpoint.name(), NotificationChannel.PUSH.name(),
                logicalKey, deliveryKey, now, expiresAt, payload,
                LocalDateTime.now(clock));
        if (created.isEmpty()) {
            // enqueue SQL이 ACTIVE·revision을 다시 확인한다. 그 사이 여정이 종료되거나
            // 재탐색되면 테스트 알림도 운영 알림과 동일하게 조용히 폐기한다.
            throw new BusinessException(JourneyErrorCode.STALE_ROUTE_REVISION);
        }
        return new QueuedTestNotification(created.get(0), journeyId, checkpoint, now, expiresAt);
    }

    private String payload(String testId, JourneyCheckpoint checkpoint) {
        try {
            return objectMapper.createObjectNode()
                    .put("adminTest", true)
                    .put("testId", testId)
                    .put("checkpoint", checkpoint.name())
                    .toString();
        } catch (RuntimeException ignored) {
            // payload은 표시 상태가 아니라 감사용이다. 직렬화 실패 시에도 FCM 본문은
            // checkpoint로 만들 수 있으므로 null로 enqueue한다.
            return null;
        }
    }

    public record QueuedTestNotification(long notificationId, long journeyId,
                                         JourneyCheckpoint checkpoint, Instant queuedAt,
                                         Instant expiresAt) {
    }
}
