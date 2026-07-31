package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

/**
 * 여정 한 단계의 조회용 모습.
 *
 * <p>{@code selectedRouteOptionId} 가 비어 있고 {@code routeOptions} 가 여럿이면 "아직 어느
 * 노선을 탈지 정하지 않았다"는 뜻이다. 앱은 이 상태에서 사용자에게 후보를 보여 준다.
 */
public record JourneyLegView(
        Long legId,
        int legIndex,
        JourneyLegType type,
        JourneyLegState state,
        String startStopId,
        String endStopId,
        String startName,
        String endName,
        Integer plannedDurationSec,
        String evidenceQuality,
        Long selectedRouteOptionId,
        List<JourneyRouteOptionView> routeOptions
) {

    public JourneyLegView {
        routeOptions = routeOptions == null ? List.of() : List.copyOf(routeOptions);
    }
}
