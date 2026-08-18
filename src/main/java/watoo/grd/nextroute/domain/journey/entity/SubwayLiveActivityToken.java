package watoo.grd.nextroute.domain.journey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import watoo.grd.nextroute.common.entity.BaseEntity;
import watoo.grd.nextroute.domain.push.entity.PushEnvironment;

import java.time.Instant;

/**
 * 잠금화면 Live Activity 하나의 수신 주소.
 *
 * <h2>왜 {@code user_push_token} 에 합치지 않나</h2>
 *
 * <p>수명이 다르다. FCM registration token 은 <b>기기·앱 설치</b> 단위로 앱을 지우기 전까지 살고,
 * ActivityKit push token 은 <b>Activity</b> 단위로 위젯이 끝나면 폐기되며 사는 동안에도 회전한다.
 * 한 표에 넣으면 "토큰이 회전했다"와 "기기가 바뀌었다"를 구분할 수 없고, Activity 를 끝낼 때
 * 기기 주소까지 함께 지우게 된다.
 *
 * <h2>발송에는 토큰이 둘 다 필요하다</h2>
 *
 * <p>FCM 이 {@code Message.token} 으로 기기를 찾고 {@code apns.live_activity_token} 으로 그 기기
 * 안의 어느 Activity 인지를 찾는다. 그래서 이 행이 {@link watoo.grd.nextroute.domain.push.entity.UserPushToken}
 * 을 가리킨다.
 *
 * <h2>여정 단위다</h2>
 *
 * <p>{@code legIndex}·{@code bindingVersion} 축을 여기 더하지 않는다. 재탐색으로 개정본이 올라도
 * 잠금화면의 Activity 는 그대로이므로, 축을 더하면 계승마다 앱이 토큰을 다시 등록해야 하고 그
 * 사이 push 가 통째로 끊긴다. <b>낡은 payload 를 막는 일은 outbox 가 한다.</b>
 *
 * <p>{@code activityToken} 은 개인식별에 준해 다룬다. 응답·로그·telemetry·metric tag 어디에도
 * 싣지 않는다(V39 관례).
 */
@Entity
@Getter
@Table(name = "subway_live_activity_token")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SubwayLiveActivityToken extends BaseEntity {

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "journey_id", nullable = false)
    private Long journeyId;

    /** 길이는 DTO 의 {@code @Size(max = 512)} 와 반드시 같게 유지한다 — 어긋나면 400 이 500 이 된다. */
    @Column(name = "activity_token", nullable = false, length = 512)
    private String activityToken;

    /** 이 Activity 를 들고 있는 기기. */
    @Column(name = "push_token_id", nullable = false)
    private Long pushTokenId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PushEnvironment environment;

    /**
     * provider 가 살아 있다고 보는 토큰인지.
     *
     * <p>{@code deletedAt} 과 다른 사건이다 — 그쪽은 앱이 Activity 를 끝내며 스스로 해제한 것이고,
     * 이 값은 provider 가 {@code UNREGISTERED} 라고 답해 우리가 끈 것이다. 발송 대상 조회는 둘 다 본다.
     */
    @Column(nullable = false)
    private boolean enabled;

    /** 앱이 마지막으로 이 토큰을 등록한 시각. */
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
