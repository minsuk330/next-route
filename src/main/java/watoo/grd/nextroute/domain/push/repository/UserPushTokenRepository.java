package watoo.grd.nextroute.domain.push.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import watoo.grd.nextroute.domain.push.entity.UserPushToken;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 알림 발송 주소록.
 *
 * <p>이 저장소의 소프트 삭제는 수동이다. 조회는 모두 {@code deletedAtIsNull} 을 명시한다.
 *
 * <p>탈퇴 시에는 이 표의 행을 <b>물리 삭제</b>한다. 발송 주소는 남기지 않는다(ADR 0001) —
 * soft delete 로 두면 지운 계정의 기기 주소가 표에 남는다. {@link #deleteAllByUserId} 참조.
 */
public interface UserPushTokenRepository extends JpaRepository<UserPushToken, Long> {

    /**
     * 등록·교체를 한 문장으로 한다.
     *
     * <p>같은 token 이 이미 살아 있으면 소유자를 이 사용자로 바꾼다. 기기를 물려주거나 같은 폰에서
     * 계정을 바꿔 로그인하는 경우가 실제로 있고, 그때 앞선 소유자를 떼어 내지 않으면 한 기기에 두
     * 사람의 알림이 간다.
     *
     * <p>찾고 나서 저장하는 방식을 쓰지 않는 이유. 새 token 을 두 요청이 동시에 등록하면 둘 다
     * "없음"을 읽고 둘 다 INSERT 해 한쪽이 unique 위반으로 죽는다. 트랜잭션이 이미 rollback-only 라
     * 그 자리에서 되살릴 수도 없다. ON CONFLICT 는 그 충돌을 SQL 안에서 흡수한다
     * ({@code BusAlertNotificationRepository#enqueue} 와 같은 이유).
     *
     * <p>ON CONFLICT 뒤의 WHERE 는 중재 인덱스 추론 절이다. {@code uq_user_push_token_token} 의
     * 술어와 글자 그대로 같아야 한다 — 다르면 "no unique or exclusion constraint matching the
     * ON CONFLICT specification" 으로 실패한다.
     *
     * <p>{@code enabled} 를 true 로 되돌린다. provider 가 UNREGISTERED 라고 해서 껐더라도, 앱이
     * 지금 이 token 으로 등록하고 있다는 사실이 그 판정보다 새롭다.
     *
     * <p>JPA auditing 을 타지 않으므로 {@code created_at}·{@code updated_at} 을 직접 넣는다.
     *
     * <p>{@code @Modifying} 이 아니다(RETURNING). 반드시 {@code @Transactional} 안에서 부른다.
     *
     * <p>남은 위험: READ COMMITTED 에서 중재 행이 동시에 삭제되는 좁은 경우 unique 위반이 여전히
     * 날 수 있다(PostgreSQL 문서화된 코너 케이스). 앱 실행당 1회 호출이라 확률이 무시할 수준이므로
     * 재시도 루프를 두지 않는다.
     *
     * <p>{@code quietUpdateSupported} 가 null 이면(능력 목록을 안 보낸 옛 앱) 기존 값을 그대로 둔다.
     *
     * @return 등록되거나 갱신된 행의 id. {@code DO UPDATE} 라 항상 1건이다
     */
    @Query(value = """
            INSERT INTO user_push_token
                (user_id, platform, provider, token, environment, enabled, last_seen_at,
                 quiet_update_supported, created_at, updated_at)
            VALUES (:userId, :platform, :provider, :token, :environment, true,
                    :seenAt, COALESCE(:quietUpdateSupported, false), :now, :now)
            ON CONFLICT (token) WHERE deleted_at IS NULL
            DO UPDATE SET user_id      = EXCLUDED.user_id,
                          platform     = EXCLUDED.platform,
                          provider     = EXCLUDED.provider,
                          environment  = EXCLUDED.environment,
                          enabled      = true,
                          last_seen_at = EXCLUDED.last_seen_at,
                          quiet_update_supported = COALESCE(:quietUpdateSupported,
                                                            user_push_token.quiet_update_supported),
                          updated_at   = EXCLUDED.updated_at
            RETURNING id
            """, nativeQuery = true)
    Long register(@Param("userId") Long userId,
                  @Param("platform") String platform,
                  @Param("provider") String provider,
                  @Param("token") String token,
                  @Param("environment") String environment,
                  @Param("seenAt") Instant seenAt,
                  @Param("quietUpdateSupported") Boolean quietUpdateSupported,
                  @Param("now") LocalDateTime now);

    /**
     * 해제. 소유자를 쿼리 조건에 넣는다 — 남의 token 을 꺼 버릴 수 있으면 안 된다.
     *
     * <p>0행이어도 정상이다. 호출부는 그대로 204 를 준다. 404 를 주면 임의 token 문자열이 우리
     * 표에 있는지 없는지가 샌다.
     *
     * @return 해제된 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE user_push_token
               SET deleted_at = :now, updated_at = :now
             WHERE user_id = :userId AND token = :token AND deleted_at IS NULL
            """, nativeQuery = true)
    int unregister(@Param("userId") Long userId,
                   @Param("token") String token,
                   @Param("now") LocalDateTime now);

    /**
     * 그 사용자의 살아 있는 token 행 하나를 token 문자열로 찾는다.
     *
     * <p>Live Activity 토큰 등록이 쓴다 — 발송에 두 토큰이 다 필요해서 Activity 행이 이 행을
     * 가리켜야 한다. {@code userId} 를 조건에 넣는 이유는 유일성이 token 하나에만 걸려 있어
     * (V39) 남의 행을 집을 수 있기 때문이다.
     *
     * <p>{@code enabled} 는 보지 않는다. provider 가 죽었다고 답한 뒤라도 앱이 지금 Activity 를
     * 등록하고 있다면 그 기기는 살아 있고, 재등록이 오면 {@code enabled} 는 어차피 되돌아간다.
     */
    Optional<UserPushToken> findByUserIdAndTokenAndDeletedAtIsNull(Long userId, String token);

    /**
     * 살아 있는 발송 대상 전부. 조건이 둘 다 필요하다 — 사용자가 해제한 것도, provider 가
     * 죽었다고 답한 것도 보내면 안 된다.
     *
     * <p>발송 경로는 이 목록이 아니라
     * {@link #findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc}
     * 를 쓴다. 이 메서드는 표의 상태를 통째로 보는 검증·운영 조회용이다.
     */
    List<UserPushToken> findByUserIdAndEnabledTrueAndDeletedAtIsNull(Long userId);

    /**
     * <b>발송 대상 한 대.</b> 가장 최근에 등록한 살아 있는 token 하나를 고른다.
     *
     * <p>표는 사용자당 여러 행을 허용하지만(V39) <b>알림은 기기 한 대에만 보낸다</b>. 같은 알림을
     * 폰과 태블릿에 동시에 띄우는 것이 요구사항이 아니고, 여러 대에 보내면 행 하나의 결말을
     * 기기별 결과에서 접어야 해 규칙이 크게 늘어난다(부분 실패 판정·순서 보존·대량 비활성화 방어).
     *
     * <p>기준은 {@code last_seen_at} 이다. 앱이 가장 최근에 등록한 기기가 사용자가 지금 들고
     * 있는 기기일 가능성이 가장 높다. 같은 시각이면 나중에 만들어진 행이 이긴다.
     *
     * <p>고른 token 이 죽어 있으면 그 알림 한 건은 실패한다. 다만 그 응답이
     * {@code UNREGISTERED} 면 그 행을 꺼 두므로 다음 알림부터는 그다음 기기가 대상이 된다.
     */
    Optional<UserPushToken>
            findFirstByUserIdAndEnabledTrueAndDeletedAtIsNullOrderByLastSeenAtDescIdDesc(
                    Long userId);

    /**
     * provider 가 {@code UNREGISTERED} 라고 답한 token 을 끈다.
     *
     * <p><b>조건부 UPDATE 인 것이 핵심이다.</b> 발송을 시작한 뒤 결과가 오기까지 사이에 같은
     * token 이 재등록될 수 있고, 그 upsert 는 같은 행의 {@code user_id}·{@code enabled}·
     * {@code last_seen_at} 을 갱신한다. {@code WHERE id = :id} 만으로 끄면 <b>방금 등록한
     * 기기</b>(혹은 새 소유자)를 꺼 버린다. 그래서 발송 시점에 <b>관측한</b> 소유자와
     * {@code last_seen_at} 을 조건에 넣는다.
     *
     * <p>0행은 정상이다 — "더 새로운 등록이 이겼다"는 뜻이므로 호출부는 로그만 남긴다.
     *
     * @return 실제로 꺼진 행 수(0 또는 1)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE user_push_token
               SET enabled = false, updated_at = :now
             WHERE id = :id
               AND user_id = :observedUserId
               AND last_seen_at = :observedLastSeenAt
               AND enabled = true
               AND deleted_at IS NULL
            """, nativeQuery = true)
    int deactivate(@Param("id") Long id,
                   @Param("observedUserId") Long observedUserId,
                   @Param("observedLastSeenAt") Instant observedLastSeenAt,
                   @Param("now") LocalDateTime now);

    /**
     * 탈퇴: 그 사용자의 push 토큰을 물리 삭제한다. 발송 주소는 남기지 않는다(ADR 0001).
     */
    @Modifying(clearAutomatically = true)
    @Query("delete from UserPushToken t where t.userId = :userId")
    void deleteAllByUserId(@Param("userId") Long userId);
}
