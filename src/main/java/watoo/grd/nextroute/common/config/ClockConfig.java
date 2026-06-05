package watoo.grd.nextroute.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 수집/예산 등 시간 기준을 Asia/Seoul로 고정한다. 서버 timezone(예: Docker UTC)에 흔들리지 않도록
 * 시각 생성은 전부 이 Clock을 통한다.
 */
@Configuration
public class ClockConfig {

	public static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Bean
	public Clock clock() {
		return Clock.system(KST);
	}
}
