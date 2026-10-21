package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * 경로 한 구간(legacy 응답 모양).
 *
 * <p><b>호환 생성자를 두지 않는다</b>(subway-express S2) — 새 칸을 빠뜨린 복사 경로가 소리 없이 값을 잃지 않게, 모든
 * 생성 지점이 {@code trainKind} 를 명시한다.
 *
 * @param trainType ODsay 기차 등급(정수). 열차 종류({@code trainKind})와 다른 값이다 — 재사용하지 않는다
 * @param trainKind 철도 구간 열차 종류 {@code NORMAL}·{@code EXPRESS}·{@code DIRECT}·{@code ITX}(subway-express S2,
 *                  {@code TrainKind#name()}). 버스·도보·ODsay 원천은 null 이고 응답에서 빠진다
 */
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
        List<TransferArrival> transferArrivals,
        // 열차 종류 (철도 구간만, subway-express S2)
        @JsonInclude(JsonInclude.Include.NON_NULL) String trainKind
) {
}
