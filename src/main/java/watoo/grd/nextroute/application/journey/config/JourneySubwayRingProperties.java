package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 지하철 진행 링 설정.
 *
 * <p><b>서버는 진행 숫자를 자주 push 하지 않는다.</b> 실시간 시간 앵커를 만들어 두면 앱이 그
 * 사이를 로컬에서 보간한다. 그래서 여기 있는 값 대부분은 "앱이 얼마나 오래, 어디까지 스스로
 * 채워도 되는가"를 정한다.
 *
 * <p><b>관문 숫자는 전부 잠정값이다.</b> 실기기 관측 전에는 근거가 없어 코드가 아니라 설정에
 * 둔다. 코드에 상수로 박으면 실측 뒤에 고칠 자리가 배포와 묶인다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.ring")
public class JourneySubwayRingProperties {

    /** 기본값 off. 갱신기가 없으면 링은 만들어지지 않고 스냅샷의 안내도 비어 있다. */
    private boolean enabled = false;

    /** 갱신 주기. 수집이 끝난 뒤 다음 tick 까지의 대기가 링 지연에 그대로 더해진다(#371). */
    private long tickMs = 2000;

    /** 한 tick 에서 볼 최대 고정 수. */
    private int batchSize = 100;

    /**
     * 이동 중 진행률의 상한.
     *
     * <p>도착 전에 100% 를 찍으면 사용자는 이미 내렸어야 한다고 읽는다. 실제 도착 사건이 오기
     * 전에는 여기서 멈춘다.
     */
    private BigDecimal progressTarget = new BigDecimal("0.90");

    /** 다음 역 <b>진입</b>. 도착과 가른다 — 진입은 아직 문이 안 열렸다. */
    private BigDecimal approachProgress = new BigDecimal("0.95");

    /**
     * 진입을 봤을 때 지금 화면 값에서 {@link #approachProgress} 까지 잇는 직선의 길이(#483). 0 이면 예전처럼 곧바로 박는다.
     * 느린 TYPICAL 직선 위에서 진입이 오면 한 번에 30%p 넘게 뛰었다(journey 290).
     */
    private Duration approachRamp = Duration.ofSeconds(6);

    /**
     * 앱이 로컬 보간을 이어가도 되는 폭({@code progressValidUntil}). <b>원천 시각({@code recptnDt})</b>부터 잰다.
     *
     * <p>길면 죽은 화면이 살아 있는 척하고, 짧으면 링이 자주 멈춘다. 실기기 관측 전에는 근거가
     * 없는 값이라 설정으로 둔다. STALE 판정은 {@link #staleAfter} 가 따로 맡는다(PR-2).
     */
    private Duration freshnessWindow = Duration.ofSeconds(120);

    // ── TYPICAL 직선(지하철 링 정차·전형 시간, docs/plan/subway-ring-typical-progress) ─────────────

    /** GTFS 계획 시간에 곱하는 여유 배수. 실측(MEASURED)·실시간(LIVE)에는 곱하지 않는다. */
    private BigDecimal typicalFactor = new BigDecimal("1.2");

    /** GTFS 에서 정차 시간을 모를 때 쓰는 전역 정차 시간. */
    private Duration defaultDwell = Duration.ofSeconds(30);

    /**
     * STALE 판정 폭 — <b>서버 시계</b> 기준. 서버가 우리 열차의 새 근거를 마지막으로 본 시각({@code sourceSeenAt})부터 잰다.
     * 원천 시각 기준으로 재면 {@code recptnDt} 자체가 1~2분 뒤처져 있어 대부분이 STALE 이 된다.
     */
    private Duration staleAfter = Duration.ofSeconds(60);

    /** 서버가 새로 봤더라도 원천 시각이 이보다 오래됐으면 STALE — 원천이 묵은 행을 계속 내보내는 경우의 이중 가드. */
    private Duration staleSourceMaxAge = Duration.ofSeconds(240);

    /** 예정이 이만큼 더 지나야 LATE 로 본다. */
    private Duration lateGrace = Duration.ofSeconds(30);

    /**
     * 직선의 시작점은 원천 사건 시각({@code recptnDt})이다(#371). 다만 서버가 처음 본 시각보다 이만큼 넘게 이른 원천은
     * "처음 본 시각 − 이 값"으로 당겨 쓴다 — 원천이 1~2분 늦게 보이면 그리자마자 예정이 지나 LATE 로 뛴다(journey 202).
     */
    private Duration maxSourceLag = Duration.ofSeconds(30);

    /**
     * ring 키 TTL. <b>반드시 둔다.</b>
     *
     * <p>수명주기 네 접점이 지우지만, 그 경로 중 하나라도 새면 TTL 없는 키가 쌓여
     * {@code allkeys-lru} 가 다른 캐시를 밀어낸다.
     */
    private Duration ttl = Duration.ofMinutes(30);

    private final Push push = new Push();

    /**
     * Live Activity 원격 갱신(push).
     *
     * <p>링 계산과 <b>스위치를 따로 둔다</b>. 링만 켜면 앱이 폴링으로 읽어 그리고, push 까지
     * 켜면 잠금화면이 스스로 갱신된다. 앱이 ActivityKit 토큰을 등록하기 전에 push 를 켜면 보낼
     * 주소가 없어 전량 실패로 보이므로, 두 단계를 나눠 켤 수 있어야 한다.
     */
    @Getter
    @Setter
    public static class Push {

        /** 기본값 off. <b>모바일 토큰 등록이 확인된 뒤에 켠다.</b> */
        private boolean enabled = false;

        /** 발송 배치 주기. 링 tick 보다 촘촘할 이유가 없다. */
        private long relayIntervalMs = 2000;

        /** 한 배치가 집어 갈 최대 건수. */
        private int batchSize = 100;

        /**
         * 최신인 동안의 재시도 상한.
         *
         * <p>무한 재시도는 latest-wins 와 충돌한다. 다만 0 으로 두면 commit 은 됐는데 push 가
         * 유실됐을 때 화면이 멈춘 채로 남는다.
         */
        private int maxAttempts = 3;

        /**
         * push 유효기간. 이 시각을 넘긴 진행률은 보내지 않는다.
         *
         * <p>{@code freshnessWindow} 와 같은 값을 쓰지 않는다 — 그쪽은 <b>앱이 보간해도 되는
         * 폭</b>이고 이쪽은 <b>배달이 늦어도 되는 폭</b>이다. 축이 다르다.
         */
        private Duration ttl = Duration.ofMinutes(2);

        /**
         * 이만큼 {@code SENDING} 에 머문 행은 워커가 죽은 것으로 보고 회수한다.
         *
         * <p>FCM 호출 시한보다 넉넉해야 한다 — 짧으면 아직 발송 중인 행을 회수해 같은 화면이
         * 두 번 나간다.
         */
        private Duration claimTimeout = Duration.ofMinutes(1);

        /** FCM 비동기 호출에 거는 시한. */
        private long sendTimeoutMs = 5000;
    }
}
