package watoo.grd.nextroute.application.route.dto;

public record PathInfo(
        int totalTime,
        int payment,
        int totalWalk,
        int transferCount,
        String firstStartStation,
        String lastEndStation
) {
}
