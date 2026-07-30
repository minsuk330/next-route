package watoo.grd.nextroute.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.auth.entity.AuthIdentity;
import watoo.grd.nextroute.domain.auth.entity.AuthProvider;

import java.util.List;
import java.util.Optional;

/**
 * 신분증 조회.
 *
 * <p>이 저장소는 지운 표시가 된 행을 자동으로 걸러 주는 장치를 쓰지 않는다. 쿼리마다
 * {@code DeletedAtIsNull} 을 직접 붙이는 관례이므로, 신원 조회 메서드는 처음부터 조건을 포함해 만든다.
 * 빠뜨리면 지워진 계정으로 로그인이 된다.
 */
public interface AuthIdentityRepository extends JpaRepository<AuthIdentity, Long> {

    Optional<AuthIdentity> findByProviderAndProviderSubjectAndDeletedAtIsNull(
            AuthProvider provider, String providerSubject);

    List<AuthIdentity> findByUserIdAndDeletedAtIsNull(Long userId);

    boolean existsByUserIdAndProviderAndDeletedAtIsNull(Long userId, AuthProvider provider);
}
