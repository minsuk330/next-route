package watoo.grd.nextroute.application.push.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.push.dto.RegisterPushTokenCommand;
import watoo.grd.nextroute.application.push.port.in.RegisterPushTokenUseCase;
import watoo.grd.nextroute.application.push.port.in.UnregisterPushTokenUseCase;
import watoo.grd.nextroute.domain.push.repository.UserPushTokenRepository;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

/**
 * push token 등록·해제.
 *
 * <p>로직이 얇은 것은 의도다. 등록의 어려운 부분(동시 등록 경쟁, 기기 물려주기)은 SQL 한 문장이
 * 처리하고({@code UserPushTokenRepository#register}), 여기서는 신원과 시각만 채운다.
 *
 * <p>token 문자열을 로그·metric tag 에 남기지 않는다(REQUIREMENTS §6).
 */
@Service
@RequiredArgsConstructor
public class PushTokenService implements RegisterPushTokenUseCase, UnregisterPushTokenUseCase {

    private final UserPushTokenRepository userPushTokenRepository;
    private final Clock clock;

    @Override
    @Transactional
    public void register(Long userId, RegisterPushTokenCommand command) {
        Instant seenAt = clock.instant();
        userPushTokenRepository.register(
                userId,
                command.platform().name(),
                command.provider().name(),
                command.token(),
                command.environment().name(),
                seenAt,
                LocalDateTime.now(clock));
    }

    /**
     * 해제. 없는 token 이거나 남의 token 이면 0행이고, 그것도 성공이다 — 멱등이어야 로그아웃을
     * 여러 번 눌러도 안전하고, 404 를 주면 임의 token 의 존재 여부가 샌다.
     */
    @Override
    @Transactional
    public void unregister(Long userId, String token) {
        userPushTokenRepository.unregister(userId, token, LocalDateTime.now(clock));
    }
}
