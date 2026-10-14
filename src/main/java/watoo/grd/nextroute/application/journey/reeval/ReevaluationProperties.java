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

    /** 한 번 요청에 앞당길 여정 수 상한. 노선 장애처럼 한꺼번에 몰릴 때 선점 풀을 덮지 않게 한다. */
    private int maxJourneysPerRequest = 500;

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
