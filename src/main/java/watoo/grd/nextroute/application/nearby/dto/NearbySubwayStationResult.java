package watoo.grd.nextroute.application.nearby.dto;

public record NearbySubwayStationResult(
        String stationId,
        String stationName,
        String lineId,
        String lineName,
        double latitude,
        double longitude,
        int distanceMeters,
        /** OTP GTFS 정류장 id(#298). 단독 추적 시작 {@code boardStopId}. 기동 워밍업 전이거나 못 이으면 null */
        String gtfsStopId
) {}
