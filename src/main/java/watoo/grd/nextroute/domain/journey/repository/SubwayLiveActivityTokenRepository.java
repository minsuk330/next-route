package watoo.grd.nextroute.domain.journey.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.journey.entity.SubwayLiveActivityToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Live Activity 수신 주소록.
 *
 * <p>소프트 삭제가 수동이라 조회는 모두 {@code deleted_at IS NULL} 을 명시한다(V39 관례).
 */
public interface SubwayLiveActivityTokenRepository
        extends JpaRepository<SubwayLiveActivityToken, Long> {

    /**
     * 등록·교체를 한 문장으로 한다.
     *
     * <p>앱은 ActivityKit 토큰이 회전될 때마다 다시 보낸다. 찾고 나서 저장하는 방식을 쓰면 두
     * 요청이 동시에 올 때 둘 다 "없음"을 읽고 둘 다 INSERT 해 한쪽이 unique 위반으로 죽는다 —
     * 그때 트랜잭션은 이미 rollback-only 라 그 자리에서 되살릴 수도 없다.
     *
     * <p><b>{@code journey_id} 까지 갱신한다.</b> 같은 Activity 토큰이 다른 여정으로 다시 오는
     * 경우가 실제로 있다 — 사용자가 여정을 끝내고 바로 새 여정을 시작하면 앱이 Activity 를
     * 재사용할 수 있다. 옛 여정에 묶인 채로 두면 새 여정의 push 가 그 토큰을 못 찾는다.
     *
     * <p>ON CONFLICT 뒤의 WHERE 는 중재 인덱스 추론 절이다. {@code uq_subway_la_token} 의 술어와
     * <b>글자 그대로</b> 같아야 한다 — 다르면 "no unique or exclusion constraint matching the
     * ON CONFLICT specification" 으로 실패한다.
     *
     * <p>{@code enabled} 를 true 로 되돌린다. provider 가 죽었다고 답했더라도, 앱이 지금 이
     * 토큰으로 등록하고 있다는 사실이 그 판정보다 새롭다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * @return 등록되거나 갱신된 행의 id
     */
    @Query(value = """
            INSERT INTO subway_live_activity_token
                (user_id, journey_id, activity_token, push_token_id, environment,
                 enabled, last_seen_at, created_at, updated_at)
            VALUES (:userId, :journeyId, :activityToken, :pushTokenId, :environment,
                    true, :seenAt, :now, :now)
            ON CONFLICT (activity_token) WHERE deleted_at IS NULL
            DO UPDATE SET user_id       = EXCLUDED.user_id,
                          journey_id    = EXCLUDED.journey_id,
                          push_token_id = EXCLUDED.push_token_id,
                          environment   = EXCLUDED.environment,
                          enabled       = true,
                          last_seen_at  = EXCLUDED.last_seen_at,
                          updated_at    = EXCLUDED.updated_at
            RETURNING id
            """, nativeQuery = true)
    Long register(@Param("userId") Long userId,
                  @Param("journeyId") Long journeyId,
                  @Param("activityToken") String activityToken,
                  @Param("pushTokenId") Long pushTokenId,
                  @Param("environment") String environment,
                  @Param("seenAt") Instant seenAt,
                  @Param("now") LocalDateTime now);

    /**
     * 해제. 소유자를 조건에 넣는다 — 남의 Activity 를 꺼 버릴 수 있으면 안 된다.
     *
     * <p>0행이어도 정상이다. 호출부는 그대로 204 를 준다. 404 를 주면 임의 토큰 문자열이 우리
     * 표에 있는지 없는지가 샌다.
     *
     * @return 해제된 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subway_live_activity_token
               SET deleted_at = :now, updated_at = :now
             WHERE user_id = :userId AND activity_token = :activityToken
               AND deleted_at IS NULL
            """, nativeQuery = true)
    int unregister(@Param("userId") Long userId,
                   @Param("activityToken") String activityToken,
                   @Param("now") LocalDateTime now);

    /**
     * 그 여정의 살아 있는 수신 주소 전부.
     *
     * <p>여정 알림({@code UserPushToken})과 달리 <b>한 대만 고르지 않는다</b>. Activity 는 그것을
     * 띄운 기기에만 존재하므로 목록에 여러 행이 있다는 것은 실제로 여러 기기가 같은 여정의
     * 위젯을 들고 있다는 뜻이고, 그중 하나만 갱신하면 나머지는 멈춘 화면으로 남는다.
     */
    @Query("""
            select t from SubwayLiveActivityToken t
             where t.journeyId = :journeyId
               and t.enabled = true
               and t.deletedAt is null
             order by t.id
            """)
    List<SubwayLiveActivityToken> findLiveByJourneyId(@Param("journeyId") Long journeyId);

    /**
     * provider 가 {@code UNREGISTERED} 라고 답한 토큰을 끈다.
     *
     * <p><b>조건부 UPDATE 인 것이 핵심이다.</b> 발송을 시작한 뒤 결과가 오기까지 사이에 같은
     * 토큰이 재등록될 수 있고, 그 upsert 는 같은 행의 {@code last_seen_at} 을 갱신한다.
     * {@code WHERE id = :id} 만으로 끄면 <b>방금 등록한 Activity</b> 를 꺼 버린다.
     *
     * <p>0행은 정상이다 — "더 새로운 등록이 이겼다"는 뜻이다.
     *
     * @return 실제로 꺼진 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE subway_live_activity_token
               SET enabled = false, updated_at = :now
             WHERE id = :id
               AND last_seen_at = :observedLastSeenAt
               AND enabled = true
               AND deleted_at IS NULL
            """, nativeQuery = true)
    int deactivate(@Param("id") Long id,
                   @Param("observedLastSeenAt") Instant observedLastSeenAt,
                   @Param("now") LocalDateTime now);

    /**
     * 여정이 끝났다. 그 여정의 토큰을 전부 해제한다.
     *
     * <p>종료 push 를 <b>보낸 뒤에</b> 불러야 한다 — 먼저 지우면 보낼 주소가 없어져 잠금화면에
     * 죽은 안내가 그대로 남는다.
     *
     * @return 해제된 행 수
     */
    @Modifying(flushAutomatically = true, clearAutomatically = false)
    @Query(value = """
            UPDATE subway_live_activity_token
               SET deleted_at = :now, updated_at = :now
             WHERE journey_id = :journeyId AND deleted_at IS NULL
            """, nativeQuery = true)
    int releaseByJourney(@Param("journeyId") Long journeyId, @Param("now") LocalDateTime now);
}
