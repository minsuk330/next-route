package watoo.grd.nextroute.application.journey.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 역에서 탈 열차를 사용자가 고르는 기능(#265). <b>일반 여정에도 영향이 있어</b> 단독 추적 플래그와 따로 켠다.
 * 꺼져 있으면 후보·선택 API 둘 다 404.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "journey.subway.selection")
public class JourneySubwaySelectionProperties {

    private boolean enabled = false;

    /** 보여 줄 후보 수(도착 순). */
    private int candidateCount = 2;
}
