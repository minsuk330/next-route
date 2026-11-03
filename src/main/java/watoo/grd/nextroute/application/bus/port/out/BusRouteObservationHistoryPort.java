package watoo.grd.nextroute.application.bus.port.out;

import watoo.grd.nextroute.application.bus.dto.BusPositionInfo;

import java.util.List;

/**
 * 노선별 최근 위치 관측 이력(ML rt_* 피처용).
 *
 * <p>ML serving 은 "앞차가 이 구간을 지나는 데 걸린 시간", "앞차와의 간격" 같은 피처를
 * 요청에 실린 노선 이력에서 계산한다({@code ml/realtime_features.py}). 앱은 위치 응답을 받을
 * 때마다 여기에 덧붙이고(수집기·검색 miss 경로 모두), 예측 요청에 최근 이력을 실어 보낸다.
 *
 * <p>이력은 best-effort 다. 없으면 serving 이 해당 피처를 NaN 으로 두고 예측한다 — 모델은
 * 학습 때 그 경우도 봤다. 따라서 저장·조회 실패는 예측을 막지 않는다.
 */
public interface BusRouteObservationHistoryPort {

    void append(String routeId, List<BusPositionInfo> positions);

    /** 보존 기간 안의 관측, 순서 무관. 이력이 없거나 조회에 실패하면 빈 목록. */
    List<Observation> recent(String routeId);

    /** @param snapshotEpochSeconds 차량 dataTm(관측 시각), epoch seconds. */
    record Observation(String vehicleId, int sectionOrder, long snapshotEpochSeconds) {
    }

    /** 이력을 쌓지도 돌려주지도 않는 구현. 테스트와 기능 off 에 쓴다. */
    BusRouteObservationHistoryPort NONE = new BusRouteObservationHistoryPort() {
        @Override
        public void append(String routeId, List<BusPositionInfo> positions) {
        }

        @Override
        public List<Observation> recent(String routeId) {
            return List.of();
        }
    };
}
