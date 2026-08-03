package watoo.grd.nextroute.application.journey.dto;

/** 저장된 노선 후보 하나의 조회용 모습. */
public record JourneyRouteOptionView(
        Long optionId,
        int optionIndex,
        int laneIndex,
        String routeId,
        String sourceRouteId,
        String routeName,
        String directionKey,
        Integer wayCode,
        Integer boardingSeq,
        Integer alightingSeq
) {
}
