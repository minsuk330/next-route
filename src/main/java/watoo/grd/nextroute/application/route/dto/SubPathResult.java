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
        List<WalkStep> walkSteps,
        // ODSAY 식별자 (trafficType == 2 버스 구간)
        String startLocalStationID,
        String endLocalStationID,
        String startArsID,
        String endArsID,
        Integer endID,
        // 도보 TMAP 실측 소요시간 (초, trafficType == 3일 때만 non-null)
        Integer walkTotalTimeSeconds,
        // 환승 도착예측 결과 (lane별, trafficType == 2일 때만 non-null)
        List<TransferArrival> transferArrivals
) {

    /** 폴리라인만 갈아끼운다. 버스 구간 형상을 안내 지도와 같은 원천으로 맞출 때 쓴다. */
    public SubPathResult withPolyline(List<CoordPoint> replacement) {
        return new SubPathResult(trafficType, sectionTime, distance, lanes, stations,
                startName, endName, startX, startY, endX, endY, trainType, payment,
                startId, way, wayCode, replacement,
                startExitNo, startExitX, startExitY, endExitNo, endExitX, endExitY,
                walkSteps, startLocalStationID, endLocalStationID, startArsID, endArsID, endID,
                walkTotalTimeSeconds, transferArrivals);
    }
}
