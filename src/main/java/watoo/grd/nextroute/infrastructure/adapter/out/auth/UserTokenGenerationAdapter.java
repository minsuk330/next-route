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
 */
@Component
@RequiredArgsConstructor
public class UserTokenGenerationAdapter implements TokenGenerationPort {

    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public OptionalInt currentGeneration(long userId) {
        return userRepository.findById(userId)
                .map(user -> OptionalInt.of(user.getTokenGeneration()))
                .orElseGet(OptionalInt::empty);
    }
}
