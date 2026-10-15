package watoo.grd.nextroute.infrastructure.adapter.out.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.application.auth.port.out.TokenGenerationPort;
import watoo.grd.nextroute.domain.user.repository.UserRepository;

import java.util.OptionalInt;

/**
 * 세대 번호를 {@code users} 에서 읽는다.
 *
 * <p>기본키 단건 조회다. 요청 경로는 이 클래스를 직접 쓰지 않고 {@link CachedTokenGenerationAdapter} 를 거친다 —
 * 부하 사다리(#427)에서 이 조회가 요청마다 커넥션을 빌려 풀을 다투는 것이 재어졌다.
 *
 * <p>탈퇴한 사용자는 세대가 맞아도 {@code empty} 를 돌려준다. 필터는 {@code empty} 를
 * "없는 사용자"로 읽어 토큰을 거부하므로, 탈퇴가 곧 전 토큰 무효가 된다.
 */
@Component
@RequiredArgsConstructor
public class UserTokenGenerationAdapter implements TokenGenerationPort {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public OptionalInt currentGeneration(long userId) {
        return userRepository.findTokenGenerationOfActive(userId)
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }
}
