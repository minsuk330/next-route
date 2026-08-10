package watoo.grd.nextroute.application.push.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * provider 가 죽었다고 답한 token 을 끈다.
 *
 * <p>{@code REQUIRES_NEW} 다. relay 는 트랜잭션 밖에서 돌고 발송 결과 기록도 각자
 * {@code REQUIRES_NEW} 로 돈다. 여기만 트랜잭션 없이 두면 조용히 안 꺼진다 — 이 코드베이스의
 * Spring Data 쿼리는 트랜잭션 밖에서도 예외 없이 통과하므로 "안 붙여도 되던데요"가 성립한다.
 *
 * <p><b>끄는 대상은 {@code UNREGISTERED} 하나뿐이다.</b> {@code INVALID_ARGUMENT} 는 payload·
 * TTL·크기 문제로도 나고 {@code SENDER_ID_MISMATCH} 는 서버 자격증명이 틀린 것이다. 둘을
 * 비활성화 사유로 쓰면 설정 실수 한 번이 주소록을 지운다. 그 판단은 발송기가 하고, 여기는
 * 넘어온 한 건만 처리한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PushTokenDeactivationService {

    private final UserPushTokenRepository userPushTokenRepository;
    private final Clock clock;

    /**
     * @param observed 발송 시점에 본 (id, userId, lastSeenAt). 조건부 UPDATE 의 근거다
     * @return 꺼진 행 수(0 또는 1). 0 은 정상 — 더 새로운 등록이 이겼다는 뜻이다
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deactivateIfSafe(ObservedToken observed) {
        int deactivated = userPushTokenRepository.deactivate(
                observed.id(), observed.userId(), observed.lastSeenAt(),
                LocalDateTime.now(clock));
        if (deactivated == 0) {
            log.debug("[JourneyNotification] 재등록이 이겨 끄지 않았다. tokenId={}", observed.id());
        }
        return deactivated;
    }

    /**
     * 발송 시점에 관측한 token 의 신원. <b>token 문자열은 담지 않는다</b> — 로그·지표·예외 어디에도
     * 실으면 안 되는 값이라 애초에 들고 다니지 않는다(REQUIREMENTS §6).
     */
    public record ObservedToken(Long id, Long userId, Instant lastSeenAt) {
    }
}
