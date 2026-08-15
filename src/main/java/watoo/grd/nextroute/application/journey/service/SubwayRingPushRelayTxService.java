package watoo.grd.nextroute.application.journey.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.journey.config.JourneySubwayRingProperties;
import watoo.grd.nextroute.domain.journey.entity.SubwayBindingStatus;
import watoo.grd.nextroute.domain.journey.entity.SubwayLegBinding;
import watoo.grd.nextroute.domain.journey.entity.SubwayLiveActivityToken;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushEvent;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushOutbox;
import watoo.grd.nextroute.domain.journey.entity.SubwayRingPushStatus;
import watoo.grd.nextroute.domain.journey.repository.SubwayLegBindingRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayLiveActivityTokenRepository;
import watoo.grd.nextroute.domain.journey.repository.SubwayRingPushOutboxRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 대기열의 <b>짧은 쓰기</b>만 모은다.
 *
 * <p>FCM 왕복은 트랜잭션 밖이어야 한다. 안에 넣으면 provider 가 느린 동안 그 행의 잠금이 유지돼
 * 갱신 tick 이 새 대기 행을 넣지 못한다 — 링이 빨라야 할 때 정확히 느려진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SubwayRingPushRelayTxService {

    private final SubwayRingPushOutboxRepository outboxRepository;
    private final SubwayLegBindingRepository bindingRepository;
    private final SubwayLiveActivityTokenRepository tokenRepository;
    private final JourneySubwayRingProperties properties;
    private final Clock clock;

    /** {@code PENDING → SENDING}. 잠긴 행은 건너뛴다. */
    @Transactional
    public List<Long> claim() {
        return outboxRepository.claimBatch(Instant.now(clock),
                properties.getPush().getBatchSize(), LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public Optional<SubwayRingPushOutbox> find(Long id) {
        return outboxRepository.findById(id);
    }

    @Transactional(readOnly = true)
    public List<SubwayLiveActivityToken> tokensOf(Long journeyId) {
        return tokenRepository.findLiveByJourneyId(journeyId);
    }

    /**
     * <b>발송 직전 재확인.</b> 이 검사가 이 PR 의 가장 미묘한 자리다.
     *
     * <p>부분 unique 는 {@code WHERE status = 'PENDING'} 이라, 워커가 행을
     * {@code PENDING → SENDING} 으로 옮기는 순간 그 행은 술어에서 <b>빠진다</b>. 그때 갱신 tick 이
     * 새 {@code PENDING} 을 넣을 수 있어 {@code SENDING} 과 {@code PENDING} 이 동시에 존재한다.
     * unique 하나로는 이 창을 못 막는다.
     *
     * <p>세대까지 보는 이유. {@code inheritTo()} 가 {@code version} 을 0 으로 되돌리므로 개정본만
     * 재확인하면 <b>같은 개정본 안에서 일어난 리셋</b>을 못 잡는다. 그러면 옛 열차 기준으로 만든
     * 화면이 새 열차의 Activity 로 나간다.
     *
     * <p>종료({@code end})는 세대를 보지 않는다. 여정이 끝나는 중이라 고정이 이미 접혔을 수 있고,
     * 그때 안 보내면 잠금화면에 죽은 안내가 남는다.
     *
     * @return 보내도 되면 비어 있다. 보내면 안 되는 이유가 있으면 그 이유
     */
    @Transactional(readOnly = true)
    public Optional<String> reasonToSkip(SubwayRingPushOutbox row) {
        long latest = outboxRepository.findMaxSequence(
                row.getJourneyId(), row.getRouteRevision(), row.getLegIndex());
        if (row.getSequence() < latest) {
            return Optional.of("superseded");
        }
        if (row.eventType() == SubwayRingPushEvent.END) {
            return Optional.empty();
        }
        if (row.getExpiresAt().isBefore(Instant.now(clock))) {
            // 늦게 뜬 진행률은 거짓말이다. 종료와 달리 이건 안 보내는 편이 낫다.
            return Optional.of("expired");
        }
        SubwayLegBinding binding = bindingRepository
                .findByJourneyIdAndRouteRevisionAndLegIndexAndDeletedAtIsNull(
                        row.getJourneyId(), row.getRouteRevision(), row.getLegIndex())
                .orElse(null);
        if (binding == null || binding.getStatus() != SubwayBindingStatus.CONFIRMED) {
            return Optional.of("binding_gone");
        }
        if (binding.getVersion() != row.getBindingVersion()) {
            return Optional.of("stale_generation");
        }
        return Optional.empty();
    }

    @Transactional
    public void markSent(Long id) {
        outboxRepository.markSent(id, Instant.now(clock), LocalDateTime.now(clock));
    }

    @Transactional
    public void markSkipped(Long id, String reason) {
        outboxRepository.markSkipped(id, reason, LocalDateTime.now(clock));
    }

    /**
     * 실패를 판정한다. 되돌릴지 접을지는 저장소의 한 문장이 정한다.
     *
     * <h2>여기서 충돌을 삼키지 않는다</h2>
     *
     * <p>{@code PENDING} 재등록이 대기 unique 와 충돌할 수 있는 좁은 창이 남는다(판정 문장이
     * 최신성을 읽은 뒤 쓰기 전에 더 새 행이 커밋되는 경우). 그 충돌을 <b>이 트랜잭션 안에서</b>
     * 잡아 {@code SKIPPED} 로 고쳐 쓰려고 하면 안 된다 — PostgreSQL 은 문장 하나가 실패하면
     * 그 트랜잭션을 통째로 abort 상태로 만들고, 이후의 모든 문장이
     * {@code current transaction is aborted}(25P02) 로 거절된다. savepoint 없이는 같은
     * 트랜잭션에서 복구 쓰기를 할 수 없다.
     *
     * <p>그래서 충돌은 <b>그대로 던진다</b>. 이 트랜잭션은 롤백되고(판정도 반영되지 않는다),
     * 정리는 부르는 쪽이 <b>새 트랜잭션</b>에서 한다({@code SubwayRingPushRelayService}).
     *
     * @return 되돌렸으면 true(재시도가 남았다)
     * @throws org.springframework.dao.DataIntegrityViolationException 그 사이 더 새 행이 생겼다
     */
    @Transactional
    public boolean resolveFailure(Long id, String reason) {
        outboxRepository.resolveFailure(id, reason,
                properties.getPush().getMaxAttempts(), Instant.now(clock),
                LocalDateTime.now(clock));
        return outboxRepository.findById(id)
                .map(row -> row.getStatus() == SubwayRingPushStatus.PENDING)
                .orElse(false);
    }

    /**
     * provider 가 죽었다고 답한 Activity 주소를 끈다.
     *
     * <p>조건부 UPDATE 다. 발송을 시작한 뒤 결과가 오기까지 사이에 같은 토큰이 재등록될 수 있고,
     * 그때 {@code WHERE id} 만으로 끄면 <b>방금 등록한 Activity</b> 를 꺼 버린다.
     */
    @Transactional
    public void deactivateToken(SubwayLiveActivityToken token) {
        int off = tokenRepository.deactivate(token.getId(), token.getLastSeenAt(),
                LocalDateTime.now(clock));
        if (off == 0) {
            // 더 새로운 등록이 이겼다. 정상이다.
            log.debug("[SubwayRingPush] 토큰 비활성화가 더 새 등록에 밀렸다");
        }
    }

    /**
     * 종료를 보낸 뒤 그 여정의 주소를 정리한다.
     *
     * <p><b>보낸 뒤에만</b> 부른다. 먼저 지우면 보낼 주소가 없어져 잠금화면에 죽은 안내가 남는다.
     */
    @Transactional
    public void releaseTokens(Long journeyId) {
        tokenRepository.releaseByJourney(journeyId, LocalDateTime.now(clock));
    }

    /** 워커가 죽어 고착된 행을 되돌리고, 유효기간이 지난 대기 행을 접는다. */
    @Transactional
    public int sweep() {
        Instant now = Instant.now(clock);
        LocalDateTime auditNow = LocalDateTime.now(clock);
        int reclaimed = outboxRepository.reclaimStale(
                now.minus(properties.getPush().getClaimTimeout()), auditNow);
        int expired = outboxRepository.expireStale(now, auditNow);
        return reclaimed + expired;
    }
}
