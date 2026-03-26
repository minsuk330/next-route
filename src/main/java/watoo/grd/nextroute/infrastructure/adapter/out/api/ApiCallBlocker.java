package watoo.grd.nextroute.infrastructure.adapter.out.api;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Component
public class ApiCallBlocker {

	private static class BlockState {
		final AtomicBoolean blocked = new AtomicBoolean(false);
		final AtomicReference<LocalDate> blockedDate = new AtomicReference<>(null);
	}

	private final ConcurrentHashMap<String, BlockState> states = new ConcurrentHashMap<>();

	public boolean isBlocked(String apiName) {
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
		BlockState state = states.computeIfAbsent(apiName, k -> new BlockState());
		if (state.blocked.compareAndSet(false, true)) {
			state.blockedDate.set(LocalDate.now());
			log.warn("[ApiBlocker] '{}' BLOCKED due to error response. Will reset at midnight.", apiName);
		}
	}
}
