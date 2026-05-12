package watoo.grd.nextroute.application.route.dto;

import java.util.List;

public record SubPathResult(
        int trafficType,
        int sectionTime,
        Double distance,
        List<LaneResult> lanes,
        List<StationResult> stations,
        String startName,
        String endName,
        Double startX,
        Double startY,
        Double endX,
        Double endY,
        Integer trainType,
        Integer payment,
        // 지하철 구간 전용 (trafficType == 1일 때만 non-null)
        Integer startId,
        String way,
        Integer wayCode,
        // 차량/도보 공용 폴리라인 슬롯
        List<CoordPoint> polyline,
        // 지하철 출구 정보 (trafficType == 1일 때만 non-null)
        String startExitNo,
        Double startExitX,
        Double startExitY,
        String endExitNo,
        Double endExitX,
        Double endExitY,
        // 도보 보강 (trafficType == 3일 때만 non-null)
        List<WalkStep> walkSteps
) {
}
