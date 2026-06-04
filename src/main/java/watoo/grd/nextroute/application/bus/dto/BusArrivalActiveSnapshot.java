package watoo.grd.nextroute.application.bus.dto;

import java.time.LocalDateTime;

public record BusArrivalActiveSnapshot(
		BusArrivalCandidate candidate,
		LocalDateTime firstSeenAt,
		LocalDateTime lastSeenAt,
		LocalDateTime lastCollectedAt
) {

	public static BusArrivalActiveSnapshot from(BusArrivalCandidate candidate, BusArrivalActiveSnapshot previous) {
		LocalDateTime observedAt = candidate.collectedAt();
		LocalDateTime firstSeenAt = previous == null ? observedAt : previous.firstSeenAt();
		return new BusArrivalActiveSnapshot(candidate, firstSeenAt, observedAt, candidate.collectedAt());
	}

	public String identityKey() {
		return candidate.identityKey();
	}
}
