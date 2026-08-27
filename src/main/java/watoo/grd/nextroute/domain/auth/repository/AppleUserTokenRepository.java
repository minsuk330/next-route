package watoo.grd.nextroute.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.auth.entity.AppleUserToken;

import java.util.Optional;

public interface AppleUserTokenRepository extends JpaRepository<AppleUserToken, Long> {

    Optional<AppleUserToken> findByUserId(Long userId);

    /**
     * 탈퇴: 그 사용자의 애플 토큰을 물리 삭제한다(ADR 0001).
     *
     * <p>파생 delete 가 아니라 벌크 DML 인 것이 중요하다 — 파생 delete 는 영속성 컨텍스트의 remove
     * 에 의존하는데, 탈퇴 트랜잭션의 뒤이은 벌크 삭제가 {@code clearAutomatically} 로 컨텍스트를
     * 비우면 아직 flush 안 된 remove 가 유실된다({@code TossUserTokenRepository} 와 같은 함정).
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from AppleUserToken t where t.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
