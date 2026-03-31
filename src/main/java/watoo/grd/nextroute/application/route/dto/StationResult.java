package watoo.grd.nextroute.application.route.dto;

public record StationResult(
        int index,
        String stationID,
        String stationName,
        Double x,
        Double y
) {
}
