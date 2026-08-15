package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.application.journey.observability.SubwayRingMetrics;
import watoo.grd.nextroute.application.journey.port.out.LiveActivityPushPort;
import watoo.grd.nextroute.domain.journey.entity.SubwayLiveActivityToken;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushOutbox;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushStatus;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 대기열을 집어 실제로 보낸다.
 *
 * <h2>보내기 전에 한 번 더 본다</h2>
 *
 * <p>집어 온 뒤 보내기까지 사이에 더 새 화면이 만들어질 수 있다. 그 창을 unique 제약으로는 못
 * 막으므로(집어 오는 순간 술어에서 빠진다) 발송 <b>직전</b>에 최신성과 고정 세대를 다시 본다.
 *
 * <h2>Activity 는 기기마다 따로 있다</h2>
 *
 * <p>여정 알림은 가장 최근 기기 <b>한 대</b>에만 가지만, 잠금화면 위젯은 그것을 띄운 기기에만
 * 존재한다. 한 대만 갱신하면 나머지는 멈춘 화면으로 남으므로 살아 있는 주소 전부에 보낸다.
 *
 * <h2>한 건의 실패는 그 건만 죽인다</h2>
 *
 * <p>배치 하나가 통째로 죽으면 그 순간 진행 중인 모든 여정의 잠금화면이 함께 멈춘다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayRingPushRelayService {

    private final SubwayRingPushRelayTxService txService;
    /**
     * 발송 창구. <b>없을 수 있다.</b>
     *
     * <p>어댑터는 자격증명이 있을 때만 뜬다. 여기서 필수 의존으로 받으면 push 를 끈 기본 설정에서
     * <b>부팅이 통째로 죽는다</b> — 쓰지도 않을 기능 때문에.
     *
     * <p>켜 놓고 창구가 없는 조합은 {@code SubwayRingPushWiringValidator} 가 읽을 수 있는
     * 문장으로 막는다. 여기서 조용히 넘기는 것은 <b>꺼져 있을 때</b>뿐이다.
     */
    private final ObjectProvider<LiveActivityPushPort> pushPortProvider;
    private final UserPushTokenRepository pushTokenRepository;
    private final SubwayRingMetrics metrics;
    private final JourneySubwayRingProperties properties;
    private final Clock clock;

    /** @return 집어 온 건수 */
    public int runOnce() {
        if (!properties.getPush().isEnabled()) {
            return 0;
        }
        List<Long> claimed = txService.claim();
        for (Long id : claimed) {
            try {
                processOne(id);
            } catch (Exception e) {
                metrics.recordPushResult("failed", "exception");
                log.warn("[SubwayRingPush] 발송 실패 id={}: {}", id, e.getMessage());
                // 정리 자체가 또 실패해도 이 건만 죽어야 한다. 여기서 던지면 남은 배치가
                // 통째로 이번 tick 을 건너뛴다.
                resolve(id, shortReason(e));
            }
        }
        return claimed.size();
    }

    /** 고착 행 회수와 유효기간 만료. */
    public int sweep() {
        return txService.sweep();
    }

    // ── 한 건 ──────────────────────────────────────────────────────────────

    private void processOne(Long id) {
        SubwayRingPushOutbox row = txService.find(id).orElse(null);
        if (row == null || row.getStatus() != SubwayRingPushStatus.SENDING) {
            return;
        }
        Optional<String> skip = txService.reasonToSkip(row);
        if (skip.isPresent()) {
            txService.markSkipped(id, skip.get());
            metrics.recordPushResult("skipped", skip.get());
            return;
        }
        List<SubwayLiveActivityToken> tokens = txService.tokensOf(row.getJourneyId());
        if (tokens.isEmpty()) {
            // 앱이 아직 Activity 를 등록하지 않았거나 이미 끝냈다. 실패가 아니다.
            txService.markSkipped(id, "no_token");
            metrics.recordPushResult("skipped", "no_token");
            return;
        }
        dispatch(row, tokens);
    }

    private void dispatch(SubwayRingPushOutbox row, List<SubwayLiveActivityToken> tokens) {
        boolean anyDelivered = false;
        String lastError = "unknown";
        Map<Long, UserPushToken> devices = devicesOf(tokens);

        for (SubwayLiveActivityToken token : tokens) {
            String deviceToken = deviceTokenOf(token, devices);
            if (deviceToken == null) {
                // 기기 주소가 해제됐다. 이 Activity 는 더 이상 닿을 수 없다.
                lastError = "device_token_gone";
                continue;
            }
            LiveActivityPushPort.PushResult result = pushPortProvider.getObject().send(deviceToken,
                    token.getActivityToken(), row.eventType().wireValue(),
                    row.getApsTimestamp(), row.getPayload());
            if (result.success()) {
                anyDelivered = true;
                continue;
            }
            lastError = result.errorCode();
            if (result.isTokenDead()) {
                // 그 Activity 는 사라졌다. 지우지는 않는다 — 그 수를 세야 하고, 재등록되면 되살아난다.
                txService.deactivateToken(token);
            }
        }
        finish(row, anyDelivered, lastError);
    }

    private void finish(SubwayRingPushOutbox row, boolean delivered, String lastError) {
        if (delivered) {
            txService.markSent(row.getId());
            metrics.recordPushResult("sent", row.getReason());
            metrics.recordPushLatency(latencyOf(row));
            if (row.eventType() == SubwayRingPushEvent.END) {
                // 보낸 뒤에만 지운다. 먼저 지우면 보낼 주소가 없어진다.
                txService.releaseTokens(row.getJourneyId());
            }
            return;
        }
        boolean retrying = resolve(row.getId(), lastError);
        metrics.recordPushResult(retrying ? "retrying" : "failed", lastError);
        if (retrying) {
            metrics.recordPushRetry(row.isCritical());
        }
    }

    /**
     * 실패를 판정하되, <b>판정 자체가 경합에 지면 새 트랜잭션에서 접는다.</b>
     *
     * <p>판정 문장이 대기 unique 와 충돌하면 그 트랜잭션은 PostgreSQL 규칙상 통째로 abort 된다 —
     * 같은 트랜잭션에서는 복구 쓰기를 할 수 없다. 이 메서드는 트랜잭션 밖이라 각 호출이 자기
     * 트랜잭션을 열고, 그래서 여기서 정리할 수 있다.
     *
     * <p>충돌은 <b>더 새 화면이 이미 대기 중</b>이라는 뜻이므로 실패가 아니라 구형이다.
     *
     * @return 재시도로 되돌렸으면 true
     */
    private boolean resolve(Long id, String reason) {
        try {
            return txService.resolveFailure(id, reason);
        } catch (DataIntegrityViolationException e) {
            txService.markSkipped(id, "superseded");
            metrics.recordPushResult("skipped", "superseded");
            return false;
        } catch (Exception e) {
            // 정리에 실패해도 행은 SENDING 으로 남고 고착 회수가 되돌린다. 여기서 던지면
            // 남은 배치가 죽는다.
            log.warn("[SubwayRingPush] 실패 판정 실패 id={}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * 그 행이 쓸 기기 주소를 <b>한 번에</b> 읽는다.
     *
     * <p>토큰마다 따로 물으면 배치 크기 × 기기 수만큼 단건 조회가 2초마다 돈다 — 링이 빨라야 할
     * 때 정확히 DB 왕복이 늘어난다.
     */
    private Map<Long, UserPushToken> devicesOf(List<SubwayLiveActivityToken> tokens) {
        List<Long> ids = tokens.stream()
                .map(SubwayLiveActivityToken::getPushTokenId)
                .distinct()
                .toList();
        return pushTokenRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(UserPushToken::getId, device -> device));
    }

    /**
     * 어느 기기로 보낼 것인가.
     *
     * <p>Activity 행이 가리키는 기기 주소를 그대로 쓴다. "가장 최근 기기"를 다시 고르지 않는다 —
     * 그러면 A 기기의 Activity 토큰을 B 기기 주소로 보내게 되고, APNs 는 그 조합을 조용히 버린다.
     */
    private String deviceTokenOf(SubwayLiveActivityToken token, Map<Long, UserPushToken> devices) {
        UserPushToken device = devices.get(token.getPushTokenId());
        if (device == null || device.getDeletedAt() != null || !device.isEnabled()) {
            return null;
        }
        return device.getToken();
    }

    /**
     * 대기열에 들어간 순간부터 지금까지.
     *
     * <p>{@code createdAt} 을 쓰지 않는다. 그 값은 {@code LocalDateTime} 이라 시간대를 가정해야
     * 하는데, 그 가정이 틀리면 지연 지표가 몇 시간씩 어긋난 채 그럴듯해 보인다.
     * {@code expiresAt} 은 {@code Instant} 이고 <b>넣을 때 시각 + TTL</b> 이라 역산이 정확하다.
     */
    private Duration latencyOf(SubwayRingPushOutbox row) {
        Instant enqueuedAt = row.getExpiresAt().minus(properties.getPush().getTtl());
        return Duration.between(enqueuedAt, Instant.now(clock));
    }

    /** 예외 문구를 그대로 옮기지 않는다 — 토큰이 들어 있을 수 있고 칼럼도 200자다. */
    private String shortReason(Exception e) {
        String name = e.getClass().getSimpleName();
        return name.length() > 60 ? name.substring(0, 60) : name;
    }
}
