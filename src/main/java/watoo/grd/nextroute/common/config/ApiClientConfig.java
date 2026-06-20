package watoo.grd.nextroute.common.config;

import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;

import java.time.Duration;
import java.util.concurrent.Semaphore;

@Configuration
public class ApiClientConfig {

	@Bean
	public RestTemplate restTemplate(RestTemplateBuilder builder) {
		return builder
				.connectTimeout(Duration.ofSeconds(5))
				.readTimeout(Duration.ofSeconds(30))
				.build();
	}

	/**
	 * 공용 RestClient(TMAP 등 unqualified 주입의 기본). ML 전용 bean이 추가되어
	 * RestClient bean이 둘이 되므로 @Primary로 unqualified 주입 모호성을 제거한다.
	 */
	@Bean
	@Primary
	public RestClient restClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofSeconds(3));
		factory.setReadTimeout(Duration.ofSeconds(5));
		return RestClient.builder()
				.requestFactory(factory)
				.build();
	}

	/** ML serving 전용 RestClient. 검색 경로에서 호출하므로 단축 timeout. @Qualifier로 주입. */
	@Bean
	public RestClient mlPredictorRestClient(MlPredictorProperties properties) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofMillis(properties.getTimeoutMs()));
		factory.setReadTimeout(Duration.ofMillis(properties.getTimeoutMs()));
		return RestClient.builder()
				.requestFactory(factory)
				.build();
	}

	/** 경로검색 전용 버스 실시간 RestClient. 재시도 없음, 단축 timeout. @Qualifier로 주입. */
	@Bean
	public RestClient busRealtimeRestClient() {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(Duration.ofMillis(1500));
		factory.setReadTimeout(Duration.ofMillis(1500));
		return RestClient.builder()
				.requestFactory(factory)
				.build();
	}

	/** 검색 fan-out provider 동시 호출 제한 Semaphore. 0 이하면 사실상 무제한. */
	@Bean
	public Semaphore busSearchConcurrencyLimiter(TransferArrivalProperties props) {
		int n = props.getMaxConcurrentExternalCalls();
		return new Semaphore(n > 0 ? n : Integer.MAX_VALUE, true);
	}
}
