package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;
import watoo.grd.nextroute.domain.journey.entity.JourneyLegType;

import java.util.List;

/**
 * 여정 한 단계의 조회용 모습.
 *
 * <p>ODsay 첫 lane을 현재 선택 노선으로 사용하고 나머지는 대체 후보로 보존한다. 이 선택은
 * 실제 차량·운행 회차를 식별한다는 뜻이 아니다.
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
        String routeId,
        Integer plannedDurationSec,
        String evidenceQuality,
        Long selectedRouteOptionId,
        List<JourneyRouteOptionView> routeOptions
) {

    public JourneyLegView {
        routeOptions = routeOptions == null ? List.of() : List.copyOf(routeOptions);
    }
}
