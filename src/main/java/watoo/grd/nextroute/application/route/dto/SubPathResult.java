package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;

/**
 * 경로 한 구간(legacy 응답 모양).
 *
 * <p><b>호환 생성자를 두지 않는다</b>(subway-express S2) — 새 칸을 빠뜨린 복사 경로가 소리 없이 값을 잃지 않게, 모든
 * 생성 지점이 {@code trainKind} 를 명시한다.
 *
 * @param trainType ODsay 기차 등급(정수). 열차 종류({@code trainKind})와 다른 값이다 — 재사용하지 않는다
 * @param plannedWaitSeconds 이 구간 탑승 전 OTP 계획 대기(초). 경로의 합 = OTP waitingTime(= {@code info.totalTime} 에 든 대기)
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
        @JsonInclude(JsonInclude.Include.NON_NULL) String trainKind,
        // OTP 계획 시각 (대중교통 구간만, search-regional-bus-wait PR-1a). ODsay·도보·잘라 낸 구간은 null
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant plannedBoardAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant plannedAlightAt,
        // 이 구간 탑승 전 OTP 계획 대기(초) = 탑승 − 직전 구간 끝. 경로 합이 OTP waitingTime(= info.totalTime 에 든 대기)과 같다.
        // 앱은 버스 대기를 더할 때 이 값을 빼야 환승 대기를 두 번 세지 않는다
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer plannedWaitSeconds
) {
    /** 계획 시각만 바꾼 복사본. */
    public SubPathResult withPlanned(Instant boardAt, Instant alightAt, Integer waitSeconds) {
        return new SubPathResult(trafficType, sectionTime, distance, lanes, stations, startName, endName,
                startX, startY, endX, endY, trainType, payment, startId, way, wayCode, polyline,
                startExitNo, startExitX, startExitY, endExitNo, endExitX, endExitY, walkSteps,
                startLocalStationID, endLocalStationID, startArsID, endArsID, endID,
                walkTotalTimeSeconds, transferArrivals, trainKind, boardAt, alightAt, waitSeconds);
    }

    /** 계획 시각을 다른 구간에서 옮겨 싣는다(복사본을 만드는 보강 단계가 잃지 않게). */
    public SubPathResult withPlannedFrom(SubPathResult source) {
        return withPlanned(source.plannedBoardAt(), source.plannedAlightAt(), source.plannedWaitSeconds());
    }
}
