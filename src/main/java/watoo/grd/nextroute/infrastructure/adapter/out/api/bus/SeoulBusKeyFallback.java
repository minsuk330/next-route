package watoo.grd.nextroute.infrastructure.adapter.out.api.bus;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 서울 버스 API(ws.bus.go.kr) 인증키 두 개 사이를 기능(path)별로 오간다.
 *
 * <p>키를 재발급하면 공급자 쪽 반영이 상세기능마다 따로 끝난다(2026-11-28: 재발급 10시간 뒤에도 위치, 노선 전체 도착은
 * 옛 키만, 나머지는 새 키만 받았다). 그래서 401 을 받으면 다른 키로 한 번 더 부르고, 통한 키를 그 기능의 기본으로 기억한다.
 * 기억한 키가 다시 401 이면 반대쪽으로 넘어가므로 공급자 반영이 끝나면 저절로 새 키로 돌아온다.
 *
 * <p>{@code seoul.api.bus-fallback-key} 가 비어 있으면 아무 일도 하지 않는다. 키 값은 로그에 남기지 않는다.
 */
@Slf4j
@Component
public class SeoulBusKeyFallback {

	private static final String PARAM = "serviceKey=";

	private final String primaryKey;
	private final String fallbackKey;
	/** path → 지금 fallback 키를 먼저 쓰는지. 없으면 primary. */
	private final Map<String, Boolean> useFallbackByPath = new ConcurrentHashMap<>();

	@Autowired
	public SeoulBusKeyFallback(
			@Value("${seoul.api.bus-key}") String primaryKey,
			@Value("${seoul.api.bus-fallback-key:}") String fallbackKey) {
		this.primaryKey = primaryKey;
		this.fallbackKey = fallbackKey == null || fallbackKey.isBlank() || fallbackKey.equals(primaryKey)
				? null : fallbackKey.trim();
	}

	public static SeoulBusKeyFallback disabled() {
		return new SeoulBusKeyFallback("", "");
	}

	/** primary 키로 만든 URI 를 이 기능에서 지금 통하는 키의 URI 로 바꾼다. */
	public URI preferred(URI primaryUri) {
		if (fallbackKey == null || !useFallbackByPath.getOrDefault(primaryUri.getPath(), false)) {
			return primaryUri;
		}
		return swap(primaryUri, primaryKey, fallbackKey).orElse(primaryUri);
	}

	/** 401 을 받은 URI 의 반대쪽 키 URI. 대체 키가 없으면 비어 있다. */
	public Optional<URI> alternate(URI tried) {
		if (fallbackKey == null) {
			return Optional.empty();
		}
		Optional<URI> toPrimary = swap(tried, fallbackKey, primaryKey);
		return toPrimary.isPresent() ? toPrimary : swap(tried, primaryKey, fallbackKey);
	}

	/** 401 뒤 다른 키로 성공한 URI. 이 기능의 기본 키를 그쪽으로 바꾼다. */
	public void markWorking(URI succeeded) {
		if (fallbackKey == null) {
			return;
		}
		boolean fallback = holds(succeeded, fallbackKey);
		Boolean before = useFallbackByPath.put(succeeded.getPath(), fallback);
		if (before == null || before != fallback) {
			log.warn("[SeoulBusKey] {} now uses the {} key after 401 on the other one",
					succeeded.getPath(), fallback ? "fallback" : "primary");
		}
	}

	private static Optional<URI> swap(URI uri, String from, String to) {
		if (!holds(uri, from)) {
			return Optional.empty();
		}
		String raw = uri.toString();
		int at = raw.indexOf(PARAM + from);
		return Optional.of(URI.create(
				raw.substring(0, at) + PARAM + to + raw.substring(at + PARAM.length() + from.length())));
	}

	private static boolean holds(URI uri, String key) {
		String raw = uri.toString();
		int at = raw.indexOf(PARAM + key);
		if (at < 0) {
			return false;
		}
		int end = at + PARAM.length() + key.length();
		return end == raw.length() || raw.charAt(end) == '&';
	}
}
