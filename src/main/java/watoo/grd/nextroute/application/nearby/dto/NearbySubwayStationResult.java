package watoo.grd.nextroute.application.nearby.dto;

public record NearbySubwayStationResult(
        String stationId,
        String stationName,
        String lineId,
        String lineName,
        double latitude,
        double longitude,
        int distanceMeters
) {}
