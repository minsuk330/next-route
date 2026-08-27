package watoo.grd.nextroute.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
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

    /**
     * 탈퇴: 그 사용자의 신원을 물리 삭제한다. 로그인 수단이 남으면 재가입을 막고 개인정보가 남는다.
     *
     * <p>{@code deleted_at} 을 보지 않는다 — 탈퇴는 살았든 지워졌든 그 사용자의 흔적을 통째로 없앤다.
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from AuthIdentity a where a.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
