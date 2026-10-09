package watoo.grd.nextroute.application.nearby.dto;

public record NearbyBusStopResult(
        String stopId,
        String stopName,
        String arsId,
        double latitude,
        double longitude,
        int distanceMeters,
        boolean supportsPrediction,
        /** OTP GTFS 정류장 id(#298). 단독 추적 시작에 그대로 넘긴다. 서울 9자리가 아니면 null */
        String gtfsStopId
) {}
