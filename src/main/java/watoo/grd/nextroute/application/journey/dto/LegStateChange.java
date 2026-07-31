package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.domain.journey.entity.JourneyLegState;

/**
 * 단계 하나를 어떤 상태로 바꿀지.
 *
 * @param legIndex 바꿀 단계의 순번
 * @param state    바뀐 뒤의 상태
 */
public record LegStateChange(int legIndex, JourneyLegState state) {
}
