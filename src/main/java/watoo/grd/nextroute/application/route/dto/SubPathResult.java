package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record SubPathResult(
        int trafficType,
        int sectionTime,
        Integer distance,
        List<LaneResult> lanes,
        List<StationResult> stations,
        String startName,
        String endName
) {
}
