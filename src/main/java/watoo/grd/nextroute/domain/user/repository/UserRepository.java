package watoo.grd.nextroute.domain.user.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.user.entity.User;

import java.time.LocalDateTime;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByTossUserKey(Long tossUserKey);

    /**
     * 살아 있는 사용자만 찾는다. 탈퇴한 행({@code deleted_at} 이 찍힌 행)은 없는 것으로 친다.
     *
     * <p>기본 {@link #findById} 는 탈퇴한 행도 돌려준다. 세션 경로(재발급·로그아웃·내 정보)는
     * 이 메서드를 써서 탈퇴한 계정을 "없는 사용자"로 취급한다. 빠뜨리면 탈퇴한 refresh 가
     * 세대만 맞으면 통과한다.
     */
    Optional<User> findByIdAndDeletedAtIsNull(Long id);

    /**
     * 살아 있는 사용자의 토큰 세대 번호. 탈퇴한 행이면 비어 있다.
     *
     * <p>요청마다 필터가 부르는 단건 조회다. 엔티티 대신 값 하나만 읽고, 탈퇴 여부를
     * 조회 조건에 넣어 필터가 별도 판정 없이 탈퇴 계정을 걸러내게 한다.
     */
    @Query("select u.tokenGeneration from User u where u.id = :id and u.deletedAt is null")
    Optional<Integer> findTokenGenerationOfActive(@Param("id") Long id);

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

    /**
     * 탈퇴: 사용자 행을 소프트 삭제하고, 세대 번호를 올려 이전 토큰을 끊고, 토스 신원을 지운다.
     *
     * <p><b>벌크 update 한 문장으로 처리하는 것이 핵심이다.</b> 관리 엔티티를 dirty-checking 으로
     * 고치면, 같은 트랜잭션의 다른 벌크 삭제가 {@code clearAutomatically = true} 로 컨텍스트를
     * 비울 때 이 변경이 flush 되지 않고 유실된다(JourneyNotificationRepository 가 같은 함정을 적어
     * 뒀다). 벌크로 처리하면 detach 여부와 무관하게 안전하다.
     *
     * @return 실제로 탈퇴 처리된 행 수(0 또는 1). 이미 탈퇴한 행이면 0
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            update User u
               set u.deletedAt = :now,
                   u.tokenGeneration = u.tokenGeneration + 1,
                   u.tossUserKey = null
             where u.id = :userId and u.deletedAt is null
            """)
    int withdraw(@Param("userId") Long userId, @Param("now") LocalDateTime now);
}
