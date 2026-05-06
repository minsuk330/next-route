package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record LaneGraphicResult(
        int laneClass,               // 1=버스, 2=지하철
        int type,
        List<List<CoordPoint>> sections  // section별 좌표 목록 (실제 경위도)
) {}
