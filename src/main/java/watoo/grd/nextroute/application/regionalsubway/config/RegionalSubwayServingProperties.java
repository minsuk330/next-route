package watoo.grd.nextroute.application.regionalsubway.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 지방 지하철 시간표 제공(api). 꺼지면 departures 엔드포인트는 404, 근처 역은 지방 역을 섞지 않는다.
 *
 * <p>🔴 앱에 "시간표" 라벨(M1~M3)이 출시된 뒤에만 켠다(Opus 검토 M12) — 옛 앱은 시간표 값을 라벨 없이 보인다.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "regional-subway.serving")
public class RegionalSubwayServingProperties {

    private boolean enabled = false;
}
