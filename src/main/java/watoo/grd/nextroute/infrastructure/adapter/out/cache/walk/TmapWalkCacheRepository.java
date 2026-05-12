package watoo.grd.nextroute.infrastructure.adapter.out.cache.walk;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface TmapWalkCacheRepository extends JpaRepository<TmapWalkCacheEntity, Long> {

    Optional<TmapWalkCacheEntity> findByCacheKey(String cacheKey);

    @Modifying
    @Query("DELETE FROM TmapWalkCacheEntity")
    int deleteAllRows();

    @Modifying
    @Query("DELETE FROM TmapWalkCacheEntity e WHERE e.cacheKey LIKE CONCAT(:prefix, '%')")
    int deleteByCacheKeyPrefix(@Param("prefix") String prefix);
}
