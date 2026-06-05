package watoo.grd.nextroute.application.bus.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class BusApiCallBudget {

	private final Clock clock;
	private final AtomicInteger callCount = new AtomicInteger(0);
	private final AtomicReference<LocalDate> currentDate;

	public BusApiCallBudget(Clock clock) {
		this.clock = clock;
		this.currentDate = new AtomicReference<>(LocalDate.now(clock));
	}

	public boolean canMakeCall(int dailyBudget) {
		resetIfNewDay();
		return callCount.get() < dailyBudget;
	}

	public void recordCall() {
		resetIfNewDay();
		callCount.incrementAndGet();
	}

	public int getUsed() {
		resetIfNewDay();
		return callCount.get();
	}

	private void resetIfNewDay() {
		LocalDate today = LocalDate.now(clock);
		LocalDate stored = currentDate.get();
		if (!today.equals(stored) && currentDate.compareAndSet(stored, today)) {
			callCount.set(0);
		}
	}
}
