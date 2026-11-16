package watoo.grd.nextroute.application.journey.dto;

/**
 * 저장된 노선 후보 하나의 조회용 모습.
 *
 * <p>{@code routeDisplayColor}: 여정을 만들 때 검색 lane 의 노선 표시색({@code #RRGGBB}). 묶인 버스 노선마다 다를 수 있다(#607).
 * 이 칸이 생기기 전에 저장된 후보는 null.
 */
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
        Integer alightingSeq,
        String routeDisplayColor
) {

    /** 색 없는 후보(옛 호출부·시험용). */
    public JourneyRouteOptionView(Long optionId, int optionIndex, int laneIndex, String routeId,
                                  String sourceRouteId, String routeName, String directionKey,
                                  Integer wayCode, Integer boardingSeq, Integer alightingSeq) {
        this(optionId, optionIndex, laneIndex, routeId, sourceRouteId, routeName, directionKey,
                wayCode, boardingSeq, alightingSeq, null);
    }
}
