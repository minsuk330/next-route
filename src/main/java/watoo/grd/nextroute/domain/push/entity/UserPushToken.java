package watoo.grd.nextroute.domain.push.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;

import java.time.Instant;

/**
 * 알림을 보낼 곳 하나. 사용자와 기기 push token 을 묶는다.
 *
 * <p>JWT 는 앱이 우리를 부를 때의 신분증이고, 이 token 은 우리가 앱을 부를 때의 주소다. 방향이
 * 반대라 하나로 대신할 수 없다. 알림을 보내는 순간에는 HTTP 요청도 SecurityContext 도 없으므로
 * (스케줄러가 돈다) 주소를 미리 적어 두는 표가 필요하다.
 *
 * <p>기기 식별자는 저장하지 않는다(SC-1). 사용자당 여러 token 을 허용하고, 발송 대상은
 * "그 사용자의 살아 있는 enabled token 전부"다.
 *
 * <p>제약은 DB 가 소유한다(V39).
 * <ul>
 *   <li>{@code uq_user_push_token_token} — 살아 있는 행 기준 token 은 하나</li>
 *   <li>{@code user_push_token_platform_check} 등 — 값 목록</li>
 *   <li>{@code user_push_token_token_blank_check} — 공백 token 금지</li>
 * </ul>
 *
 * <p>{@code token} 은 개인식별에 준해 다룬다. 로그·telemetry·metric tag 어디에도 싣지 않는다
 * (REQUIREMENTS §6). 해시로 저장하는 안은 기각했다 — 발송에 평문이 필요하다.
 */
@Entity
@Table(name = "user_push_token")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserPushToken extends BaseEntity {

    /** User 연관 대신 id 로 참조한다({@code JourneySession}·{@code AuthIdentity} 와 같은 관례). */
    @Column(nullable = false)
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushPlatform platform;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushProvider provider;

    /** 길이는 DTO 의 {@code @Size(max = 512)} 와 반드시 같게 유지한다 — 어긋나면 400 이 500 이 된다. */
    @Column(nullable = false, length = 512)
    private String token;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushEnvironment environment;

    /**
     * provider 가 살아 있다고 보는 token 인지.
     *
     * <p>{@code deletedAt} 과 다른 사건이다. {@code deletedAt} 은 사용자가 스스로 해제한 것이고,
     * 이 값은 provider 가 {@code UNREGISTERED} 라고 답해 우리가 끈 것이다. 발송 대상 조회는
     * 두 조건을 모두 본다.
     */
    @Column(nullable = false)
    private boolean enabled;

    /** 앱이 마지막으로 이 token 을 등록한 시각. 오래된 행을 정리할 근거를 미리 남긴다. */
    @Column(nullable = false)
    private Instant lastSeenAt;

    @Builder
    public UserPushToken(Long userId, PushPlatform platform, PushProvider provider,
                         String token, PushEnvironment environment, Instant lastSeenAt) {
        this.userId = userId;
        this.platform = platform;
        this.provider = provider;
        this.token = token;
        this.environment = environment;
        this.enabled = true;
        this.lastSeenAt = lastSeenAt;
    }

    /**
     * provider 가 {@code UNREGISTERED}·{@code INVALID_ARGUMENT} 로 답했을 때 끈다(Phase 5 PR3).
     *
     * <p>지우지 않는 이유. 껐다는 사실 자체를 세야 하고(PR7 지표), 같은 token 이 다시 등록되면
     * 되살려야 한다.
     */
    public void deactivate() {
        this.enabled = false;
    }

    /**
     * 앱이 다시 등록해 살아 있음이 확인됐다.
     *
     * <p>{@code enabled} 를 되돌린다 — 앱이 지금 이 token 으로 등록하고 있다는 사실이 provider 의
     * 옛 판정보다 새롭다.
     */
    public void touch(Instant seenAt) {
        this.lastSeenAt = seenAt;
        this.enabled = true;
    }
}
