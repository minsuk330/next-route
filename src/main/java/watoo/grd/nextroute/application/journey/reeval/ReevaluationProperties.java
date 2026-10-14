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
