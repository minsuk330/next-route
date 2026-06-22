package watoo.grd.nextroute.domain.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import watoo.grd.nextroute.domain.auth.entity.TossUserToken;

import java.util.Optional;

public interface TossUserTokenRepository extends JpaRepository<TossUserToken, Long> {
    Optional<TossUserToken> findByUserKey(Long userKey);
    void deleteByUserKey(Long userKey);
}
