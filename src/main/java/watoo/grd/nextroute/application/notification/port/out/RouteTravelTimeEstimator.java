package watoo.grd.nextroute.application.notification.port.out;

import watoo.grd.nextroute.domain.notification.entity.RouteAlertReliability;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 출발지→도착지 소요시간 추정 추상화. notification 은 "경로 전체 검색"이 아니라 "소요시간 추정"만 의존한다
 * (캐시/ODsay 대체/ML 보정 격리 목적).
 *
 * <p>두 모드:
 * <ul>
 *   <li>{@code STATIC} — baseline/등록 시. 실시간·모델 무시. sectionTime + 도보 실측 + 버스 term/2 정적 대기.</li>
 *   <li>{@code REALTIME} — first/final check. enricher 의 실시간/모델 환승대기(waitSeconds) 반영.</li>
 * </ul>
 */
public interface RouteTravelTimeEstimator {

    /**
     * @throws IllegalStateException 경로 자체가 없을 때(422 매핑)
     * @throws RouteOutOfServiceException 후보가 전부 운행불가일 때(422 매핑)
     */
    RouteEvaluation evaluate(TravelTimeQuery query, EvaluationMode mode);

    enum EvaluationMode { STATIC, REALTIME }

    /** {@code targetArrivalAt} 은 서비스윈도(첫차/막차) 판정 기준. */
    record TravelTimeQuery(double startX, double startY, String startName,
                           double endX, double endY, String endName,
                           LocalDateTime targetArrivalAt) {
    }

    /**
     * @param maxHeadwayMinutes 선택 경로 버스구간 max(term). first check 여유 산정용(버스 없으면 null).
     */
    record RouteEvaluation(int durationSec, RouteAlertReliability reliability,
                           SelectedPath selectedPath, Integer maxHeadwayMinutes) {
    }

    /** first check 가 저장할 선택 경로 스냅샷 재료. */
    record SelectedPath(int fixedSec, List<Boarding> boardings, FinalCheckEligibility eligibility) {
    }

    enum FinalCheckEligibility {
        /** 버스 구간 있고 전부 resolve 성공 → final check 실행. */
        ELIGIBLE,
        /** 버스 구간 없음(지하철·도보 전용) → final check 생략(정상). */
        NO_BUS,
        /** 버스 있으나 stopId/routeId/seq resolve 실패 → final check 생략 + reliability LOW. */
        UNRESOLVED_BUS
    }

    record Boarding(String stopId, String routeId, int seq, int offsetSec) {
    }
}
