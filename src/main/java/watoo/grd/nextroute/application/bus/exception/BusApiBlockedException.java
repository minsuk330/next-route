package watoo.grd.nextroute.application.bus.exception;

import java.time.Instant;

public class BusApiBlockedException extends RuntimeException {

	private final Instant blockedUntil;

	public BusApiBlockedException(Instant blockedUntil, String message) {
		super(message);
		this.blockedUntil = blockedUntil;
	}

	public Instant blockedUntil() {
		return blockedUntil;
	}
}
