package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.journey.port.out.PushMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import watoo.grd.nextroute.application.journey.config.JourneyNotificationProperties;
import watoo.grd.nextroute.application.journey.dto.DetectionStamp;
import watoo.grd.nextroute.application.journey.dto.TransferRiskAssessment;
import watoo.grd.nextroute.application.journey.observability.JourneyNotificationMetrics;
import watoo.grd.nextroute.domain.journey.entity.JourneyCheckpoint;
import watoo.grd.nextroute.domain.journey.entity.JourneyNotificationScope;
import watoo.grd.nextroute.domain.journey.entity.NotificationPriority;
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

    /** 처음부터 끝까지 지표(#446)의 payload 칸 이름. 발송 접수 쪽이 같은 이름으로 읽는다. */
    public static final String FIRST_SEEN_AT = "firstSeenAt";
    public static final String SEEN_TIER = "seenTier";
    public static final String SEEN_SOURCE = "seenSource";
    public static final String HOLD_MS = "holdMs";

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
        return enqueueTransferRisk(journeyId, routeRevision, assessment, channel, java.time.Duration.ZERO);
    }

    /**
     * 늦춰 보내는 환승 위험 알림(#264 PR-5). 보드 모드에서는 대안이 있으면 재탐색 push 하나로 합친다 —
     * 그 판단(보드 갱신)이 끝날 시간을 벌려고 발송 시각을 {@code delay} 만큼 미룬다. 그 사이 재탐색 push
     * 가 나가면 이 행은 취소된다. 만료도 발송 시각 기준으로 잰다(바닥 180초가 지연에 먹히지 않게).
     */
    public String enqueueTransferRisk(long journeyId, int routeRevision,
                                      TransferRiskAssessment assessment,
                                      NotificationChannel channel, java.time.Duration delay) {
        return enqueueTransferRisk(journeyId, routeRevision, assessment, channel, delay, null).deliveryKey();
    }

    /**
     * 발송 종류를 실은 환승 위험 알림(#428 PR-1b). 피로 정책이 켜졌을 때만 부른다.
     *
     * <p>울릴 권리를 딴 쪽은 "행이 실제로 생겼는가"를 알아야 한다 — 중복이라 0행이면 울릴 알림이 없으니 권리를
     * 돌려놔야 한다. 그래서 발송 키와 생성 여부를 함께 돌려준다.
     *
     * @param delivery null 이면 payload 에 {@code delivery} 를 싣지 않는다(정책 이전과 같은 바이트)
     */
    public EnqueueResult enqueueTransferRisk(long journeyId, int routeRevision,
                                             TransferRiskAssessment assessment,
                                             NotificationChannel channel, java.time.Duration delay,
                                             PushMessage.Delivery delivery) {
        return enqueueTransferRisk(journeyId, routeRevision, assessment, channel, delay, delivery, null);
    }

    /**
     * 갈아탈 차 노선 이름까지 실은 환승 위험 알림(#428 PR-2). 이름은 발송 순간 문구("2호선이 3분 뒤 와요")의
     * 재료일 뿐 push {@code data} 로는 나가지 않는다.
     *
     * @param routeName null 이면 payload 에 싣지 않는다 — 문구는 기존 것으로 떨어진다
     */
    public EnqueueResult enqueueTransferRisk(long journeyId, int routeRevision,
                                             TransferRiskAssessment assessment,
                                             NotificationChannel channel, java.time.Duration delay,
                                             PushMessage.Delivery delivery, String routeName) {
        return enqueueTransferRisk(journeyId, routeRevision, assessment, channel, delay, delivery, routeName, null);
    }

    /**
     * 처음 본 시각까지 실은 환승 위험 알림(#446). payload 에 {@code firstSeenAt}·{@code seenTier}·{@code seenSource} 를
     * 싣고, 발송 접수 때 {@code JourneyNotificationOutboxStateService#markSent} 가 그것으로 처음부터 끝까지 지연을 잰다.
     * 이 세 칸은 서버 안에서만 쓴다 — push {@code data} 는 식별자만 나간다({@code FcmMessageFactory}).
     *
     * @param detection null 이면 싣지 않는다(지표가 그 알림을 못 잰다)
     */
    public EnqueueResult enqueueTransferRisk(long journeyId, int routeRevision,
                                             TransferRiskAssessment assessment,
                                             NotificationChannel channel, java.time.Duration delay,
                                             PushMessage.Delivery delivery, String routeName,
                                             DetectionStamp detection) {
        JourneyCheckpoint checkpoint = JourneyCheckpoint.ofTransferStatus(assessment.status());
        String logicalKey = JourneyNotificationKeys.logical(journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER, assessment.transferIndex(), checkpoint);
        String deliveryKey = JourneyNotificationKeys.delivery(logicalKey, channel);

        Instant now = Instant.now(clock).plus(delay);
        Instant expiresAt = expiresAt(assessment, now);
        String payload = writePayload(assessment, delivery, routeName, detection, delay);

        List<Long> created = notificationRepository.enqueue(
                journeyId, routeRevision,
                JourneyNotificationScope.TRANSFER.name(), assessment.transferIndex(),
                checkpoint.name(), channel.name(),
                logicalKey, deliveryKey, now, expiresAt, payload,
                NotificationPriority.of(checkpoint).value(), LocalDateTime.now(clock));

        if (!created.isEmpty()) {
            recordEnqueued(checkpoint, delivery);
        }
        return new EnqueueResult(deliveryKey, !created.isEmpty());
    }

    /**
     * 적재 한 건의 결과.
     *
     * @param deliveryKey 만들려고 시도한 발송 키(행이 생겼는지와 무관)
     * @param created     행이 새로 생겼는가. 중복·여정 종료면 false
     */
    public record EnqueueResult(String deliveryKey, boolean created) {
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
                writePayload(assessment), NotificationPriority.of(checkpoint).value(), LocalDateTime.now(clock));

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
        return enqueueRerouteSuggested(journeyId, routeRevision, proposalId, gainSeconds, channel,
                now, expiresAt, null);
    }

    /**
     * 발송 종류를 실은 재탐색 알림(#428 PR-1b).
     *
     * @param delivery null 이면 payload 에 {@code delivery} 를 싣지 않는다(정책 이전과 같은 바이트)
     */
    public boolean enqueueRerouteSuggested(long journeyId, int routeRevision, long proposalId,
                                           int gainSeconds, NotificationChannel channel,
                                           Instant now, Instant expiresAt, PushMessage.Delivery delivery) {
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
                writeReroutePayload(proposalId, gainSeconds, delivery),
                NotificationPriority.of(checkpoint).value(), LocalDateTime.now(clock));

        if (created.isEmpty()) {
            return false;
        }
        recordEnqueued(checkpoint, delivery);
        return true;
    }

    /**
     * 보드 push 한 건(#264 PR-5). 논리 키에 <b>push 순번</b>을 붙인다 — 보드는 개정본 동안 행이 하나라
     * 제안 번호만으로는 두 번째 push 가 {@code ON CONFLICT} 에 삼켜진다.
     *
     * @param payload 문구 재료(정류장·노선·이득·기점 종류). 후보 목록은 싣지 않는다 — 누르면 최신 보드를 본다
     * @return 행이 새로 생겼으면 true
     */
    public boolean enqueueBoardPush(long journeyId, int routeRevision, long proposalId, int pushSeq,
                                    Map<String, Object> payload, NotificationChannel channel,
                                    Instant now, Instant expiresAt) {
        return enqueueBoardPush(journeyId, routeRevision, proposalId, pushSeq, payload, channel,
                now, expiresAt, null);
    }

    /**
     * 발송 종류를 실은 보드 push(#428 PR-1b). 부르는 쪽의 payload 맵은 고치지 않고 복사본에 싣는다 — 채널마다
     * 같은 맵을 넘기므로 원본을 고치면 다음 채널이 엉뚱한 값을 볼 수 있다.
     *
     * @param delivery null 이면 payload 에 {@code delivery} 를 싣지 않는다(정책 이전과 같은 바이트)
     */
    public boolean enqueueBoardPush(long journeyId, int routeRevision, long proposalId, int pushSeq,
                                    Map<String, Object> payload, NotificationChannel channel,
                                    Instant now, Instant expiresAt, PushMessage.Delivery delivery) {
        JourneyCheckpoint checkpoint = JourneyCheckpoint.REROUTE_SUGGESTED;
        int scopeId = (int) proposalId;
        String logicalKey = JourneyNotificationKeys.boardPush(journeyId, routeRevision, proposalId, pushSeq);
        String deliveryKey = JourneyNotificationKeys.delivery(logicalKey, channel);
        String body;
        try {
            Map<String, Object> withDelivery = payload;
            if (delivery != null) {
                withDelivery = new LinkedHashMap<>(payload);
                withDelivery.put("delivery", delivery.name());
            }
            body = objectMapper.writeValueAsString(withDelivery);
        } catch (Exception e) {
            log.warn("[JourneyNotification] 보드 push payload 직렬화 실패 proposalId={}: {}",
                    proposalId, e.getMessage());
            body = "{}";
        }
        List<Long> created = notificationRepository.enqueue(
                journeyId, routeRevision,
                JourneyNotificationScope.JOURNEY.name(), scopeId,
                checkpoint.name(), channel.name(),
                logicalKey, deliveryKey, now, expiresAt, body,
                NotificationPriority.of(checkpoint).value(), LocalDateTime.now(clock));
        if (created.isEmpty()) {
            return false;
        }
        recordEnqueued(checkpoint, delivery);
        return true;
    }

    /**
     * 재탐색 알림의 재료.
     *
     * <p><b>후보를 싣지 않는다.</b> 제안은 스냅샷이고 사용자가 알림을 여는 시점에는 이미 지나간
     * 하차점이 있을 수 있다 — 목록은 조회 경로가 그때 다시 만들어 준다. 여기 있는 것은 "어느
     * 제안인가"와 문구용 개선폭뿐이다.
     */
    private String writeReroutePayload(long proposalId, int gainSeconds, PushMessage.Delivery delivery) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("proposalId", proposalId);
            body.put("etaGainSec", gainSeconds);
            if (delivery != null) {
                body.put("delivery", delivery.name());
            }
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
        return writePayload(assessment, null, null, null, null);
    }

    private String writePayload(TransferRiskAssessment assessment, PushMessage.Delivery delivery,
                                String routeName, DetectionStamp detection, java.time.Duration hold) {
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
            if (routeName != null) {
                body.put("routeName", routeName);
            }
            if (delivery != null) {
                body.put("delivery", delivery.name());
            }
            if (detection != null && detection.firstSeenAt() != null) {
                body.put(FIRST_SEEN_AT, detection.firstSeenAt().toString());
                body.put(SEEN_TIER, detection.tier() == null ? null : detection.tier().name());
                body.put(SEEN_SOURCE, detection.source());
                // 적재 때 정한 보류(합치기 창·보드 지연). 재시도가 scheduled_at 을 백오프 시각으로 미루므로 발송 때
                // scheduled_at − created_at 으로는 보류를 되읽을 수 없다.
                body.put(HOLD_MS, hold == null ? 0L : hold.toMillis());
            }
            return objectMapper.writeValueAsString(body);
        } catch (Exception e) {
            log.warn("[JourneyNotification] payload 직렬화 실패 journeyTransfer={}: {}",
                    assessment.transferIndex(), e.getMessage());
            return null;
        }
    }

    /** 기존 지표는 그대로 세고, 발송 종류가 있으면(피로 정책 켜짐) 종류별로 한 번 더 센다. */
    private void recordEnqueued(JourneyCheckpoint checkpoint, PushMessage.Delivery delivery) {
        metrics.recordEnqueued(checkpoint);
        if (delivery != null) {
            metrics.recordEnqueuedDelivery(checkpoint, delivery.name());
        }
    }
}
