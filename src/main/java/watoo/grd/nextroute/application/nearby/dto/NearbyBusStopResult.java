package watoo.grd.nextroute.application.nearby.dto;

public record NearbyBusStopResult(
        String stopId,
        String stopName,
        String arsId,
        double latitude,
        double longitude,
        int distanceMeters,
        boolean supportsPrediction
) {}
