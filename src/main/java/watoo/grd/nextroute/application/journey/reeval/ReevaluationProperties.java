package watoo.grd.nextroute.application.journey.reeval;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 이벤트 기반 재평가(#415-3) 운영값.
 *
 * <p><b>기본은 꺼짐 + 그림자 모드</b>다. 켜도 그림자 모드면 DB 를 고치지 않고 지표만 센다 — 실제로
 * 켜기 전에 이벤트가 얼마나 자주 오는지, 평가기에 얼마나 일을 더 얹을지 운영에서 먼저 본다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.navigation.reeval")
public class ReevaluationProperties {

    /** 트리거가 무엇이든 하는지. 꺼져 있으면 받은 요청을 전부 버린다. */
    private boolean enabled = false;

    /** 켜져 있어도 DB 를 고치지 않고 지표만 센다. */
    private boolean shadow = true;

    /**
     * 등급별로 직전 평가에서 최소 얼마 뒤에 다시 평가할지. 이벤트가 아무리 잦아도 여정당 평가 횟수를
     * 이 간격으로 묶는다. 긴급을 3초로 두면 평가기가 포화됐을 때 수요가 세 배가 돼 5초로 둔다.
     */
    private long urgentMinGapSeconds = 5;
    private long cautionMinGapSeconds = 10;
    private long relaxedMinGapSeconds = 30;

    /**
     * 한 번 요청에 앞당길 여정 수 상한. 노선 장애처럼 한꺼번에 몰릴 때 선점 풀을 덮지 않게 한다.
     *
     * <p>{@link WakeCapMode#FIXED} 면 이 값이 그대로 상한이다. {@link WakeCapMode#CAPACITY}(기본)에서는
     * 쓰지 않는다 — 대신 {@link #wakeCapMin}·{@link #wakeCapMax} 사이에서 처리량으로 계산한다.
     */
    private int maxJourneysPerRequest = 500;

    /** 깨우기 상한을 어떻게 정하나(#427 처방 2차). */
    public enum WakeCapMode {
        /** 고정값 {@code max-journeys-per-request}. 예전 동작이고, 되돌릴 때 쓴다. */
        FIXED,
        /** 최근 평가기 처리량 × 다음 수집까지 시간 × 여유 배수를 {@code [min, max]} 로 자른 값. */
        CAPACITY
    }

    /**
     * 깨우기 상한 방식. 고정 500 은 지하철 사다리 2,000 에서 평가기가 남는데도(예정→시작 p95 13초) 매 수집 정확히
     * 500 만 깨우고 29,217 건을 넘겼다 — 상한이 평가기가 아니라 숫자 하나에 묶여 있었다.
     */
    private WakeCapMode wakeCapMode = WakeCapMode.CAPACITY;

    /**
     * 다음 수집까지 시간(초) — 이번에 깨운 여정을 평가기가 이 안에 처리하면 다음 수집과 겹치지 않는다. 주 호출자인
     * 지하철 수집 주기({@code realtime.subway.poll-interval-ms} 15초)와 맞춘다.
     */
    private long wakeHorizonSeconds = 15;

    /**
     * 처리량 × 시간에 곱하는 여유 배수. 1 보다 커야 한다 — 재는 처리량은 "할 수 있는 만큼"이 아니라 "실제로 한
     * 만큼"이라, 1 이하면 일이 적을 때 잰 낮은 처리량이 상한을 낮추고 낮은 상한이 다시 처리량을 낮추는 하강
     * 되먹임이 된다. 1.5 면 수집마다 한 칸씩 위로 더듬어 올라가고, 넘친 만큼은 포화 차단(shed)이 막는다.
     */
    private double wakeHeadroom = 1.5;

    /** 계산 상한의 바닥. 막 켜져 처리량을 모를 때(콜드 스타트)·셀 수 없을 때도 이 값. 예전 고정값과 같다. */
    private int wakeCapMin = 500;

    /** 계산 상한의 천장. 노선 장애로 몰릴 때 선점 풀을 한꺼번에 덮지 않게 한다. */
    private int wakeCapMax = 5000;

    /**
     * 처리량을 재는 창(초). 최근 이 시간 안에 판정을 저장한 여정 수 ÷ 이 시간. 여정당 마지막 평가 시각만 남아
     * 창 안의 두 번째 평가는 안 세어지므로(하한), 최소 평가 간격(10초) 근처로 짧게 둔다.
     */
    private long wakeRateWindowSeconds = 10;

    /** 잰 처리량을 다시 재기까지(밀리초). 수집·감시 tick 마다 세지 않게. */
    private long wakeRateCacheMs = 5000;

    /** 예정보다 이만큼 넘게 밀린 여정 수를 포화 신호로 센다. */
    private long shedBacklogAgeSeconds = 30;

    /** 위 적체가 이 수 이상이면 주의·여유 등급은 앞당기지 않는다. 0 이하면 끄기. */
    private long shedBacklogThreshold = 200;

    /** 지하철 스냅숏 변화로 트리거할지(#415-3 PR-A2). {@link #enabled} 와 둘 다 켜야 돈다. */
    private boolean subwayTrigger = false;

    /** 관심 키 색인을 몇 초마다 다시 만들지. 그사이 시작한 여정은 시작 시 이미 "지금" 평가된다. */
    private long interestRebuildSeconds = 20;

    /** 버스 노선 감시자(#415-3 PR-B). {@link #enabled} 와 둘 다 켜야 돈다. */
    private boolean busWatcher = false;

    /**
     * 감시자 전용 일일 호출 몫(서울 버스 도착 API). 0 이하면 한 번도 부르지 않는다 — 공급자 한도를 올리기
     * 전에는 0 으로 둔다(COST-MODEL.md: 목표 부하에서 하루 100만~200만 회 필요).
     */
    private int busWatcherDailyLimit = 0;

    /**
     * 등급별 노선 조회 주기. 하한 10초는 공급자 갱신 주기 실측(중앙값 9초) — 더 자주 불러도 새 데이터가 없다.
     */
    private long busUrgentCadenceSeconds = 10;
    private long busCautionCadenceSeconds = 15;
    private long busRelaxedCadenceSeconds = 60;

    /** 한 tick 에 부를 노선 수 상한. 조회가 순서대로라 tick 하나가 길어지지 않게 한다. */
    private int busMaxRoutesPerTick = 10;

    /** 노선 조회 한 번의 기한. 재시도는 없다 — 다음 주기가 다시 부른다. */
    private long busCallTimeoutMs = 1500;

    /** tick 하나가 조회에 쓸 수 있는 시간. 공용 스케줄러 스레드를 오래 붙잡지 않게. */
    private long busTickBudgetMs = 5000;

    /** 이만큼 연달아 비거나 실패하면 그 노선은 서울 노선이 아니라고 보고 한동안 쉰다. 한 번의 장애로 쉬지 않게. */
    private int busEmptyStreakToBackoff = 3;

    /** 응답이 빈 노선(서울 노선이 아님 등)을 다시 부르기까지 쉬는 시간. */
    private long busEmptyRouteBackoffMinutes = 60;

    public long busCadenceSeconds(ReevaluationTier tier) {
        return switch (tier) {
            case URGENT -> busUrgentCadenceSeconds;
            case CAUTION -> busCautionCadenceSeconds;
            case RELAXED -> busRelaxedCadenceSeconds;
        };
    }

    public long minGapSeconds(ReevaluationTier tier) {
        return switch (tier) {
            case URGENT -> urgentMinGapSeconds;
            case CAUTION -> cautionMinGapSeconds;
            case RELAXED -> relaxedMinGapSeconds;
        };
    }

    public Duration shedBacklogAge() {
        return Duration.ofSeconds(shedBacklogAgeSeconds);
    }
}
