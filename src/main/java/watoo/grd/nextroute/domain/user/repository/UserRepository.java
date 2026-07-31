package watoo.grd.nextroute.domain.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.user.entity.User;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTossUserKey(Long tossUserKey);

    /**
     * 사용자 행을 잠그고 읽는다. 같은 사람의 요청을 한 줄로 세우는 문지기다.
     *
     * <p>여정 시작이 "진행 중인 여정이 있나 확인 → 없으면 만든다" 두 걸음이라, 동시에 두 번
     * 누르면 둘 다 "없다"를 보고 둘 다 만들려 한다. 이 잠금이 두 번째 요청을 첫 번째가 끝날
     * 때까지 세운다. 최종 방어선은 여전히 DB 의 {@code uq_journey_active_per_user} 다.
     *
     * <p>반드시 트랜잭션 안에서 호출한다. 밖에서 부르면 잠금이 즉시 풀려 아무 효과가 없다.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);
}
