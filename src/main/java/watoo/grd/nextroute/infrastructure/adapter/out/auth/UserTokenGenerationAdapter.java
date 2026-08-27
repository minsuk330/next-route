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
 * <p>기본키 단건 조회다. 캐싱은 넣지 않았다 — 무효화 시점을 하나 더 관리해야 하고,
 * 이 조회가 실제로 부담이 되는지 아직 재보지 않았다.
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
