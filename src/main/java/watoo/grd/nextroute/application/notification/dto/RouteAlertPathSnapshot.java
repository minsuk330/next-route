package watoo.grd.nextroute.application.notification.dto;

import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.FinalCheckEligibility;
import watoo.grd.nextroute.application.notification.port.out.RouteTravelTimeEstimator.SelectedPath;

import java.util.List;

/**
 * first check 가 저장하고 final check 가 읽는 선택 경로 스냅샷(JSON 직렬화 대상).
 * ODsay 재검색 없이 승차 지점만 타깃 재조회하기 위한 최소 정보.
 */
public record RouteAlertPathSnapshot(int fixedSec, FinalCheckEligibility eligibility, List<Boarding> boardings) {

    public record Boarding(String stopId, String routeId, int seq, int offsetSec, int headwayMinutes) {}

    public static RouteAlertPathSnapshot from(SelectedPath sp) {
        List<Boarding> bs = sp.boardings().stream()
                .map(b -> new Boarding(b.stopId(), b.routeId(), b.seq(), b.offsetSec(), b.headwayMinutes()))
                .toList();
        return new RouteAlertPathSnapshot(sp.fixedSec(), sp.eligibility(), bs);
    }
}
