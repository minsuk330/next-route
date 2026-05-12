package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record OdsayRoutePolylineData(List<OdsayRoutePolylinePoint> points) {

    public boolean isEmpty() {
        return points == null || points.isEmpty();
    }

    public int size() {
        return points == null ? 0 : points.size();
    }
}
