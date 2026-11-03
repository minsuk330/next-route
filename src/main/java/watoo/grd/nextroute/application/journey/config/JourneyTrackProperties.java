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

    /**
     * 근처 조회에서 진행 방향(#538)을 계산할 버스 정류장 묶음 수(거리순 앞에서부터). 묶음마다 정류장 패턴 조회가 든다.
     * 나머지는 {@code heading: null}. 운영 실측 묶음당 수 ms(2026-11-03 강남역 15회 합 121ms).
     */
    private int headingLimit = 12;

    /**
     * 진행 방향 계산에 쓰는 시간 상한(ms). 근처 목록 자체는 이것과 무관하게 돌려준다 — OTP 가 느려도 근처 조회가
     * 패턴 조회 때문에 마감(route.search.otp-ms)까지 늘어지지 않게. 넘으면 남은 정류장은 {@code heading: null}.
     */
    private long headingBudgetMs = 1000;

    /** 정류장별 패턴·패턴 상세 캐시(초). 그래프 버전이 키에 들어가 그래프 교체 시 자연히 갈린다. */
    private long patternCacheSeconds = 600;
}
