package watoo.grd.nextroute.infrastructure.adapter.out.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * API 이름별 "오늘 자정까지 차단". 기본은 JVM 메모리다. {@link SharedBlockStore} 빈이 있으면(지역 버스 공유 플래그 켬) 그 저장소가
 * 맡는 이름은 서버 사이에서 차단 상태를 공유한다 — 저장소 오류면 JVM 상태로 내려앉는다.
 */
@Slf4j
@Component
public class ApiCallBlocker {

	private final SharedBlockStore shared;

	/** JVM 전용(시험·공유 저장소 없음). */
	public ApiCallBlocker() {
		this.shared = null;
	}

	@Autowired
	public ApiCallBlocker(ObjectProvider<SharedBlockStore> shared) {
		this.shared = shared.getIfAvailable();
	}

	private static class BlockState {
		final AtomicBoolean blocked = new AtomicBoolean(false);
		final AtomicReference<LocalDate> blockedDate = new AtomicReference<>(null);
	}

	private final ConcurrentHashMap<String, BlockState> states = new ConcurrentHashMap<>();

	public boolean isBlocked(String apiName) {
		if (shared != null && shared.handles(apiName)) {
			// 공유 저장소가 "차단"이면 그대로. "아님"이어도 이 서버의 JVM 상태는 본다 — 공유 쓰기가 실패해 이 서버만 차단한
			// 경우(block 이 Redis 오류를 삼킴)를 놓치지 않게.
			Optional<Boolean> remote = shared.isBlocked(apiName);
			if (remote.isPresent() && remote.get()) {
				return true;
			}
		}
		BlockState state = states.computeIfAbsent(apiName, k -> new BlockState());
		if (!state.blocked.get()) {
			return false;
		}
		LocalDate today = LocalDate.now();
		LocalDate blockedOn = state.blockedDate.get();
		if (blockedOn != null && !today.equals(blockedOn)) {
			if (state.blocked.compareAndSet(true, false)) {
				state.blockedDate.set(null);
				log.info("[ApiBlocker] '{}' unblocked (new day: {})", apiName, today);
			}
			return false;
		}
		return true;
	}

	public void block(String apiName) {
		if (shared != null && shared.handles(apiName)) {
			shared.block(apiName);
		}
		BlockState state = states.computeIfAbsent(apiName, k -> new BlockState());
		if (state.blocked.compareAndSet(false, true)) {
			state.blockedDate.set(LocalDate.now());
			log.warn("[ApiBlocker] '{}' BLOCKED due to error response. Will reset at midnight.", apiName);
		}
	}
}
