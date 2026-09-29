package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 지하철 탑승 열차 판정 설정.
 *
 * <p><b>관문 숫자는 전부 잠정값이다.</b> 실기기 관측 전에는 근거가 없다 — 지금 값은 원천의 관측
 * 해상도(열차별 p50 56초·p90 84초)에서 역산한 것이다. 그래서 전부 설정으로 두고 기본을 끈다.
 * 코드에 상수로 박으면 실측 뒤에 고칠 자리가 배포와 묶인다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.binding")
public class JourneySubwayBindingProperties {

    /** 기본값 off. 판정기가 없으면 관측은 쌓이기만 한다. */
    private boolean enabled = false;

    /**
     * 기기 시각이 서버보다 이만큼 뒤처지면 의심한다.
     *
     * <p>의심한다고 버리지 않는다 — 버리면 시계가 틀어진 기기는 영영 안내를 못 받고, 그 사실을
     * 아무도 모른다. 표시만 남기고 판정기가 가중을 낮춘다.
     */
    private Duration maxClockPast = Duration.ofMinutes(10);

    /** 기기 시각이 서버보다 앞선 경우. 미래 시각은 뒤처진 것보다 이상하므로 더 좁게 본다. */
    private Duration maxClockFuture = Duration.ofMinutes(2);

    // ── 판정기(PR-4a) ──────────────────────────────────────────────────────

    /** 판정 주기. 원천이 60~90초마다 갱신되므로 이보다 잦을 이유가 없다. */
    private long tickMs = 2000;

    /** 한 tick 에서 볼 최대 건수. */
    private int batchSize = 50;

    /** {@code PENDING} 전용. 이동 시작 관측이 이보다 오래되면 판정을 포기한다. */
    private Duration matchWindow = Duration.ofMinutes(6);


    /** 관문 (1). 1위 후보의 오차가 이보다 크면 유일해도 고정하지 않는다. */
    private Duration absoluteMaxSkew = Duration.ofSeconds(150);

    /**
     * 관문 (2). 구간에 안 들어간 잔여 불확실성(기기 시계 오차) 전담.
     *
     * <p>사건 구간은 이미 관측 결측만큼 넓어져 있으므로 여기에 또 넣으면 이중 계산이다.
     */
    private Duration clockSkewBudget = Duration.ofSeconds(20);

    /** 센서·전송 지연. 기기 시계 오차는 여기 넣지 않는다 — 이중 계산이 된다. */
    private Duration motionUncertainty = Duration.ofSeconds(30);

    /** 관문 (3) 계수. headway 추정이 가능할 때만 적용한다. */
    private double headwayBeta = 0.5;

    /** 출발 사건을 찾을 때 이동 시작 앞뒤로 볼 폭. */
    private Duration departureLookback = Duration.ofMinutes(5);

    /** 후행 확정(L4)에서 정차 관측 앞뒤로 도착 사건을 볼 폭. */
    private Duration arrivalLookaround = Duration.ofMinutes(4);

    /**
     * 색인이 사건 구간의 <b>중간점</b> 기준이라 조회창을 이만큼 더 넓힌다.
     *
     * <p>{@code RedisSubwayTrainLogAdapter} 가 ZSET 점수로 {@code SubwayTrainEvent.estimate()} 를
     * 쓴다. 창을 그대로 쓰면 실제로는 겹치는데 중간점이 창 밖이라 후보에 오르지도 못한다.
     * 넓게 긁고 코드에서 겹침으로 다시 거른다.
     *
     * <p>사건 구간 반폭의 상한이기도 하다 — 이보다 넓은 구간은 어차피 관문 (1)을 못 넘는다.
     */
    private Duration indexHalfWidthGuard = Duration.ofMinutes(3);

    /** 후행 확정에서 후보 도착 시각이 정차 관측과 이보다 벌어지면 안 맞는 것으로 본다. */
    private Duration episodeMatchTolerance = Duration.ofSeconds(90);

    /** 감사행에 박는 판정 알고리즘 판. 관문 수식이 바뀌면 올린다. */
    private String algorithmVersion = "m7-v1";

    /**
     * 사용자 요청 경로가 고정 행을 잠글 때 기다리는 상한.
     *
     * <p>워커는 {@code SKIP LOCKED} 로 건너뛰므로 기다리지 않는다. 사용자 요청만 짧게 기다리고,
     * 넘기면 재시도 가능한 실패로 답한다. 앱이 같은 관측을 다시 보내는 것은 정상 동작이고
     * 감사행 유일성이 중복을 흡수한다(PR-3 계약).
     *
     * <p><b>JPA 힌트로는 이걸 못 한다.</b> PostgreSQL 에는 {@code FOR UPDATE WAIT n} 이 없어
     * Hibernate 가 {@code jakarta.persistence.lock.timeout} 의 임의 값을 그냥 {@code for update}
     * 로 떨어뜨린다(0=NOWAIT, -2=SKIP LOCKED 만 문장으로 나간다). 그래서 트랜잭션 안에서
     * {@code SET LOCAL lock_timeout} 을 직접 건다.
     */
    private Duration lockTimeout = Duration.ofSeconds(1);
}
