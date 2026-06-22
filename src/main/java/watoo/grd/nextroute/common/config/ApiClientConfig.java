package watoo.grd.nextroute.common.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import watoo.grd.nextroute.application.auth.config.TossLoginProperties;
import watoo.grd.nextroute.application.route.config.MlPredictorProperties;
import watoo.grd.nextroute.application.route.config.TransferArrivalProperties;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.Semaphore;

@Slf4j
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

	/**
	 * 토스 로그인 전용 RestClient. generate-token 등 서버 간 통신에 mTLS가 필요하므로
	 * 클라이언트 인증서를 담은 SSLContext 기반 JDK HttpClient로 구성한다.
	 * keystore 경로가 비어 있으면 mTLS 없이 부팅만 되도록 기본 SSLContext를 사용한다(실호출만 실패).
	 */
	@Bean
	public RestClient tossRestClient(TossLoginProperties props) {
		HttpClient.Builder httpBuilder = HttpClient.newBuilder()
				.connectTimeout(Duration.ofMillis(props.getConnectTimeoutMs()));

		SSLContext sslContext = buildSslContext(props);
		if (sslContext != null) {
			httpBuilder.sslContext(sslContext);
		}

		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpBuilder.build());
		factory.setReadTimeout(Duration.ofMillis(props.getReadTimeoutMs()));

		return RestClient.builder()
				.baseUrl(props.getBaseUrl())
				.requestFactory(factory)
				.build();
	}

	private SSLContext buildSslContext(TossLoginProperties props) {
		if (props.getKeyStorePath() == null || props.getKeyStorePath().isBlank()) {
			log.warn("[TOSS] mTLS keystore 미설정 — 토스 로그인 서버 통신은 실패한다. toss.login.key-store-path 설정 필요");
			return null;
		}
		try {
			char[] keyPw = props.getKeyStorePassword() == null
					? new char[0] : props.getKeyStorePassword().toCharArray();
			KeyStore keyStore = KeyStore.getInstance("PKCS12");
			try (InputStream in = Files.newInputStream(Path.of(props.getKeyStorePath()))) {
				keyStore.load(in, keyPw);
			}
			KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
			kmf.init(keyStore, keyPw);

			TrustManagerFactory tmf = null;
			if (props.getTrustStorePath() != null && !props.getTrustStorePath().isBlank()) {
				char[] trustPw = props.getTrustStorePassword() == null
						? new char[0] : props.getTrustStorePassword().toCharArray();
				KeyStore trustStore = KeyStore.getInstance("PKCS12");
				try (InputStream in = Files.newInputStream(Path.of(props.getTrustStorePath()))) {
					trustStore.load(in, trustPw);
				}
				tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
				tmf.init(trustStore);
			}

			SSLContext ctx = SSLContext.getInstance("TLS");
			ctx.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
			log.info("[TOSS] mTLS SSLContext 구성 완료 (truststore={})", tmf != null ? "custom" : "default");
			return ctx;
		} catch (Exception e) {
			throw new IllegalStateException("토스 mTLS SSLContext 구성 실패: " + e.getMessage(), e);
		}
	}
}
