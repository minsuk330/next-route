package watoo.grd.nextroute.application.bus.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record BusArrivalActiveSnapshot(
		BusArrivalCandidate candidate,
		LocalDateTime firstSeenAt,
		LocalDateTime lastSeenAt,
		LocalDateTime lastCollectedAt,
		Integer missedCount,
		String lifecycleId
) {

	public static BusArrivalActiveSnapshot from(BusArrivalCandidate candidate, BusArrivalActiveSnapshot previous) {
		LocalDateTime observedAt = candidate.collectedAt();
		LocalDateTime firstSeenAt = previous == null ? observedAt : previous.firstSeenAt();
		String lifecycleId = previous == null ? UUID.randomUUID().toString() : previous.lifecycleId();
		return new BusArrivalActiveSnapshot(candidate, firstSeenAt, observedAt, candidate.collectedAt(), 0, lifecycleId);
	}

	public String identityKey() {
		return candidate.identityKey();
	}

	public BusArrivalActiveSnapshot markMissing() {
		return new BusArrivalActiveSnapshot(
				candidate,
				firstSeenAt,
				lastSeenAt,
				lastCollectedAt,
				currentMissedCount() + 1,
				lifecycleId
		);
	}

	public int currentMissedCount() {
		return missedCount == null ? 0 : missedCount;
	}
}
