package watoo.grd.nextroute.domain.route.favorite.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteRoute;
import watoo.grd.nextroute.domain.route.favorite.entity.FavoriteType;
import watoo.grd.nextroute.domain.user.entity.User;

import java.util.List;
import java.util.Optional;

public interface FavoriteRouteRepository extends JpaRepository<FavoriteRoute, Long> {

    List<FavoriteRoute> findByUserAndDeletedAtIsNull(User user);

    Optional<FavoriteRoute> findByIdAndUserAndDeletedAtIsNull(Long id, User user);

    boolean existsByUserAndTypeAndDeletedAtIsNull(User user, FavoriteType type);

    /**
     * 탈퇴: 그 사용자의 즐겨찾기를 물리 삭제한다. 집·회사 좌표가 담겨 있어 남기지 않는다(ADR 0001).
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from FavoriteRoute f where f.user.id = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
