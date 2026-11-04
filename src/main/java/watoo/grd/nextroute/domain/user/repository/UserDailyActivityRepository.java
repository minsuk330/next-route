package watoo.grd.nextroute.domain.user.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import watoo.grd.nextroute.domain.user.entity.UserDailyActivity;

import java.time.Instant;
import java.time.LocalDate;

public interface UserDailyActivityRepository extends JpaRepository<UserDailyActivity, UserDailyActivity.Key> {

    /**
     * 그날 첫 활동 한 건. 이미 있으면 0행 — 여러 인스턴스·재시도가 겹쳐도 첫 값이 남는다.
     *
     * @return 넣은 행 수(0 또는 1)
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO user_daily_activity (activity_date, user_id, first_seen_at, app_build)
            VALUES (:activityDate, :userId, :firstSeenAt, :appBuild)
            ON CONFLICT (activity_date, user_id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringDuplicate(@Param("activityDate") LocalDate activityDate, @Param("userId") Long userId,
                                @Param("firstSeenAt") Instant firstSeenAt, @Param("appBuild") String appBuild);

    /** 탈퇴: 활동 기록을 물리 삭제한다(ADR 0001 — 개인 이력). */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserDailyActivity a where a.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
