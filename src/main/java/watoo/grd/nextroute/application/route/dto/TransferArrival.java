package watoo.grd.nextroute.application.route.dto;

import java.time.Instant;

public record TransferArrival(
        String routeId,
        int laneIndex,
        Source source,
        Status status,
        Instant calculatedAt,
        Instant userArrivalAt,
        Instant predictedArrivalAt,
        Long waitSeconds,
        String vehicleId,
        String modelVersion,
        Integer basisLaneIndex,
        Boolean conditional
) {
    public enum Source { REALTIME, MODEL, NONE }

    public enum Status {
        AVAILABLE,
        ARRIVAL_TIME_APPROXIMATE,
        DISABLED,
        UNSUPPORTED_ROUTE,
        STOP_MAPPING_FAILED,
        NO_VEHICLE,
        MODEL_UNAVAILABLE,
        UPSTREAM_UNAVAILABLE,
        ERROR
    }
}
