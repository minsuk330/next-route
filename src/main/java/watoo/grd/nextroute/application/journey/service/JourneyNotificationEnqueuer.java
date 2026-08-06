package watoo.grd.nextroute.application.journey.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.repository.JourneyNotificationRepository;
import watoo.grd.nextroute.domain.notification.entity.NotificationChannel;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 알림 한 줄을 outbox 에 적는다. 부르는 쪽의 트랜잭션 안에서 돈다.
 *
 * <p>키 조립·payload 직렬화·만료 시각 계산을 <b>레포지토리 호출 전에</b> 끝낸다. 그 계산이
 * 트랜잭션 안에서 예외를 내면 평가 결과 저장까지 함께 죽기 때문이다.
 *
 * <p>{@code try/catch} 로 감싸지 않는다. 이미 rollback-only 가 된 트랜잭션에서 나는 예외를
 * 삼키면 시끄러운 실패가 조용한 실패로 바뀔 뿐이다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JourneyNotificationEnqueuer {

    private final JourneyNotificationRepository notificationRepository;
    private final JourneyNotificationProperties properties;
    private final JourneyNotificationMetrics metrics;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    /**
     * 환승 위험 알림 한 건.
     *
     * @return 만들려고 시도한 발송 키. 실제로 행이 생겼는지와 무관하다 — 중복이라 0행이어도
     *         "이 키는 이번 판단이 만든 것"이므로 밀어내기 대상에서 빼야 한다
     */
    public String enqueueTransferRisk(long journeyId, int routeRevision,
                                      TransferRiskAssessment assessment,
                                      NotificationChannel channel) {
        JourneyCheckpoint checkpoint = JourneyCheckpoint.ofTransferStatus(assessment.status());
        String logicalKey = JourneyNotificationKeys.logical(journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(), checkpoint);
        String deliveryKey = JourneyNotificationKeys.delivery(logicalKey, channel);

        Instant now = Instant.now(clock);
        Instant expiresAt = expiresAt(assessment, now);
        String payload = writePayload(assessment);

        List<Long> created = notificationRepository.enqueue(
                journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER.name(), assessment.transferIndex(),
                checkpoint.name(), channel.name(),
                logicalKey, deliveryKey, now, expiresAt, payload, LocalDateTime.now(clock));

        if (!created.isEmpty()) {
            metrics.recordEnqueued(checkpoint);
        }
        return deliveryKey;
    }

    /**
     * 이 시각을 넘기면 보내지 않는다.
     *
     * <pre>
     * expiresAt = clamp(다음 차 도착 시각 ?: now + 기본, now + 바닥, now + 천장)
     * </pre>
     *
     * <p>바닥이 필요한 이유. MISSED 는 여유가 음수라는 뜻이라 다음 차 도착 시각이 <b>이미
     * 과거</b>다. 그대로 쓰면 삽입 순간 만료라 relay 가 첫 claim 에서 취소한다 — 가장 중요한
     * 알림이 사라진다.
     */
    private Instant expiresAt(TransferRiskAssessment assessment, Instant now) {
        Instant target = assessment.nextBusArrivalAt() != null
                ? assessment.nextBusArrivalAt()
                : now.plus(properties.defaultTtl());
        Instant floor = now.plus(properties.minTtl());
        Instant ceiling = now.plus(properties.maxTtl());
        if (target.isBefore(floor)) {
            return floor;
        }
        return target.isAfter(ceiling) ? ceiling : target;
    }

    /**
     * 발송 시점의 문구를 만드는 재료. 계약은 PR6 이 정하고 지금은 최소한만 싣는다.
     *
     * <p>직렬화가 실패해도 알림을 버리지 않는다. payload 없는 알림은 문구가 밋밋할 뿐이지만,
     * 여기서 예외를 내면 평가 트랜잭션 전체가 죽는다(버스 outbox 와 같은 방식).
     */
    private String writePayload(TransferRiskAssessment assessment) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("transferIndex", assessment.transferIndex());
            body.put("status", assessment.status().name());
            body.put("toLegIndex", assessment.toLegIndex());
            if (assessment.transferSlackSeconds() != null) {
                body.put("transferSlackSeconds", assessment.transferSlackSeconds());
            }
            if (assessment.nextBusArrivalAt() != null) {
                body.put("nextBusArrivalAt", assessment.nextBusArrivalAt().toString());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("[JourneyNotification] payload 직렬화 실패 journeyTransfer={}: {}",
                    assessment.transferIndex(), e.getMessage());
            return null;
        }
    }
}
