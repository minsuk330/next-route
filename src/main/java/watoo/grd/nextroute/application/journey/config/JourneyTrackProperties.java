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
}
