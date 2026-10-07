package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 단독 추적(#265, {@code kind=TRACK}).
 *
 * <p>{@code enabled} 하나가 조회·시작 API 를 함께 연다. 조회도 OTP 를 부르므로 꺼져 있으면 막는다(404).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.track")
public class JourneyTrackProperties {

    private boolean enabled = false;

    /** 좌표 주변 정류장/역 조회 반경(m). */
    private int nearRadiusMeters = 300;

    /** 좌표 주변 조회 최대 개수. */
    private int nearLimit = 30;

    /** 정류장별 패턴·패턴 상세 캐시(초). 그래프 버전이 키에 들어가 그래프 교체 시 자연히 갈린다. */
    private long patternCacheSeconds = 600;

    /** 단독 추적 버스 차량 대조(#265 PR-4). */
    private Bus bus = new Bus();

    @Getter
    @Setter
    public static class Bus {

        /** 관찰·대조 스케줄러(worker 전용)를 켠다. 꺼져 있으면 차량 칼럼은 늘 비어 있다. */
        private boolean enabled = false;

        /** 스케줄러 tick. 근접 관찰 간격과 같게 둔다. */
        private long tickMs = 15_000;

        /** 승차 정류장 근처에 차가 없을 때 노선 위치 조회 간격(초). */
        private int idlePollSeconds = 60;

        /** 승차 정류장 {@code nearStops} 정거장 안에 차가 들어왔거나 대조 대기 중일 때 간격(초). */
        private int nearPollSeconds = 15;

        private int nearStops = 2;

        /** 승차 시각 ±이 창 안에 승차 정류장을 떠난 차만 후보(초). */
        private int matchWindowSeconds = 60;

        /** 후보가 둘 이상이면 가장 가까운 것과 두 번째의 시각 차가 이 이상일 때만 고정(초). */
        private int ambiguityGapSeconds = 45;

        /** 창 안에 0대면 이만큼 더 기다렸다가 포기(관측 지연 흡수, 초). */
        private int retryAfterSeconds = 30;

        /** 기기 시각과 서버 수신 시각이 이보다 벌어지면 기기 시각을 믿지 않고 서버 시각을 쓴다(초). */
        private int maxClockSkewSeconds = 120;

        /** 출발 사건을 들고 있는 시간(초). */
        private int eventRetentionSeconds = 600;

        /** 원천별 일일 전용 상한. 넘으면 그날 대조를 포기한다(추적은 계속, 차량 없이). */
        private Budget positionBudget = new Budget();
    }

    @Getter
    @Setter
    public static class Budget {
        private int seoul = 2_000;
        private int gyeonggi = 3_000;
        private int tago = 20_000;
    }
}
