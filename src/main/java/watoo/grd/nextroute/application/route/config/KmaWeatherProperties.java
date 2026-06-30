package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 기상청 초단기실황(getUltraSrtNcst) 설정. 단일 격자 1점(서울 nx=60, ny=127).
 * enabled=false면 폴링/조회 안 함 → 날씨 feature 전부 null.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "kma.weather")
public class KmaWeatherProperties {
    private boolean enabled = false;
    private String baseUrl =
            "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtNcst";
    private int nx = 60;   // 서울
    private int ny = 127;  // 서울
    private long pollMs = 600_000;  // 10분
    private int timeoutMs = 3000;
}
