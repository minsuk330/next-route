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
     * 환승 대상 차량 접근 알림 한 건(N2).
     *
     * <p>만료는 다음 차 도착 시각 <b>그대로</b>다. 위험 알림의 바닥을 쓰지 않는다 — 그 바닥은
     * {@code MISSED} 의 도착이 이미 과거라서 필요한 장치이고, 여기서는 부르는 쪽이 "도착이 미래이고
     * 창 안"임을 이미 보장한다. 바닥을 붙이면 차가 떠난 뒤에 "곧 온다"가 나간다 — 안 가는 편이 낫다.
     *
     * <p><b>발송 키가 아니라 생성 여부를 돌려준다.</b> 위험 알림은 밀어내기 대상에서 빼려고 키가
     * 필요했지만 접근 알림은 밀어내기를 하지 않는다. 대신 "행이 실제로 생겼는가"가 필요하다 —
     * 그것이 relay 를 깨울지의 기준이다. 창이 열려 있는 동안 판정은 매 평가마다 참이라, 시도를
     * 기준으로 삼으면 아무것도 안 늘어난 채 relay 만 계속 깨운다.
     *
     * @param now 평가 한 번의 기준 시각. 조건 검사에 쓴 것과 같은 값이어야 한다
     * @return 행이 새로 생겼으면 true. 중복이라 0행이면 false
     */
    public boolean enqueueTargetApproach(long journeyId, int routeRevision,
                                         TransferRiskAssessment assessment,
                                         NotificationChannel channel, Instant now) {
        JourneyCheckpoint checkpoint = JourneyCheckpoint.TRANSFER_TARGET_APPROACHING;
        String logicalKey = JourneyNotificationKeys.logical(journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(), checkpoint);
        String deliveryKey = JourneyNotificationKeys.delivery(logicalKey, channel);

        List<Long> created = notificationRepository.enqueue(
                journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER.name(), assessment.transferIndex(),
                checkpoint.name(), channel.name(),
                logicalKey, deliveryKey, now, assessment.nextBusArrivalAt(),
                writePayload(assessment), LocalDateTime.now(clock));

        if (created.isEmpty()) {
            return false;
        }
        metrics.recordEnqueued(checkpoint);
        return true;
    }

    /**
     * "더 빠른 길이 있어요" 한 건(journey-reroute-nextstop PR-3).
     *
     * <p>범위가 {@link JourneyNotificationScope#JOURNEY} 다 — 제안은 여정당 하나이고, 가리키는
     * 자리는 환승이 아니라 <b>하차 정류장</b>일 수 있어 환승 번호로는 주소가 안 된다. 그래서
     * {@code scopeId} 는 제안 번호다: 0 으로 두면 같은 여정의 다음 제안이 같은 논리 키를 만들어
     * {@code ON CONFLICT} 에 삼켜지고, 두 번째 제안은 영영 알림이 안 나간다.
     *
     * <p>만료는 <b>제안의 만료 시각 그대로</b>다. 제안이 죽은 뒤에 도착한 알림은 눌러도 빈 목록만
     * 보여 준다.
     *
     * @return 행이 새로 생겼으면 true. 중복이라 0행이면 false(relay 를 깨울 이유가 없다)
     */
    public boolean enqueueRerouteSuggested(long journeyId, int routeRevision, long proposalId,
                                           int gainSeconds, NotificationChannel channel,
                                           Instant now, Instant expiresAt) {
        JourneyCheckpoint checkpoint = JourneyCheckpoint.REROUTE_SUGGESTED;
        int scopeId = (int) proposalId;
        String logicalKey = JourneyNotificationKeys.logical(journeyId, routeRevision,
                JourneyNotificationScope.JOURNEY, scopeId, checkpoint);
        String deliveryKey = JourneyNotificationKeys.delivery(logicalKey, channel);

        List<Long> created = notificationRepository.enqueue(
                journeyId, routeRevision,
                JourneyNotificationScope.JOURNEY.name(), scopeId,
                checkpoint.name(), channel.name(),
                logicalKey, deliveryKey, now, expiresAt,
                writeReroutePayload(proposalId, gainSeconds), LocalDateTime.now(clock));

        if (created.isEmpty()) {
            return false;
        }
        metrics.recordEnqueued(checkpoint);
        return true;
    }

    /**
     * 재탐색 알림의 재료.
     *
     * <p><b>후보를 싣지 않는다.</b> 제안은 스냅샷이고 사용자가 알림을 여는 시점에는 이미 지나간
     * 하차점이 있을 수 있다 — 목록은 조회 경로가 그때 다시 만들어 준다. 여기 있는 것은 "어느
     * 제안인가"와 문구용 개선폭뿐이다.
     */
    private String writeReroutePayload(long proposalId, int gainSeconds) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("proposalId", proposalId);
            body.put("etaGainSec", gainSeconds);
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("[JourneyNotification] payload 직렬화 실패 proposalId={}: {}",
                    proposalId, e.getMessage());
            return null;
        }
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
