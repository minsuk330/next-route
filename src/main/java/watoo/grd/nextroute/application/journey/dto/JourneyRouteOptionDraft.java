package watoo.grd.nextroute.application.journey.dto;

/**
 * 한 구간에서 탈 수 있는 노선 후보 하나. 아직 저장되지 않은 계획 값이다.
 *
 * <p>검색 응답의 {@code lanes} 한 칸이 여기 한 개가 된다. 응답이 준 순서를 {@code laneIndex} 로
 * 그대로 들고 있어서, 나중에 후보를 걸러 내더라도 "원본에서 몇 번째였나"를 잃지 않는다.
 *
 * @param optionIndex   이 단계 안에서의 후보 순번. 0 부터
 * @param laneIndex     검색 응답 {@code lanes} 배열에서의 원래 순번
 * @param routeId       서버 내부 노선 식별자. 해석 전이거나 실패하면 null
 * @param sourceRouteId 검색 응답이 준 원천 식별자(버스는 {@code busLocalBlID}, 지하철은 노선 코드)
 * @param routeName     사람이 읽는 이름(버스 번호, 노선명)
 * @param wayCode       ODSAY 지하철 방향 코드. 버스와 방향 미제공 구간은 null
 * @param boardingSeq   내부 버스 노선에서 승차 정류장의 순번. 단건 해석 실패 시 null
 * @param alightingSeq  내부 버스 노선에서 하차 정류장의 순번. 단건 해석 실패 시 null
 */
public record JourneyRouteOptionDraft(
        int optionIndex,
        int laneIndex,
        String routeId,
        String sourceRouteId,
        String routeName,
        Integer wayCode,
        Integer boardingSeq,
        Integer alightingSeq
) {

    /**
     * 원천 식별자만 채운 후보를 만든다.
     *
     * <p>{@code routeId} 를 비워 두는 것은 의도적이다. 원천 식별자를 내부 노선으로 해석하려면
     * 저장소를 뒤져야 하는데, 이 계산기는 아무것도 조회하지 않는 순수 변환으로 남긴다.
     * 해석은 실시간 대조가 실제로 필요해지는 단계의 몫이다.
     */
    public static JourneyRouteOptionDraft of(int index, String sourceRouteId, String routeName,
                                             Integer wayCode) {
        return new JourneyRouteOptionDraft(index, index, null, sourceRouteId, routeName,
                wayCode, null, null);
    }

    /** 내부 노선과 양 끝 정류장 순번까지 확보됐는지. 버스 실시간 API·ML 호출의 최소 단위다. */
    public boolean hasResolvedBusScope() {
        return hasResolvedBusBoarding() && alightingSeq != null;
    }

    /** 정류장 도착 정보 조회에 필요한 버스 노선·승차 정류장 순번까지 확보됐는지. */
    public boolean hasResolvedBusBoarding() {
        return routeId != null && !routeId.isBlank() && boardingSeq != null;
    }

    public JourneyRouteOptionDraft resolved(String resolvedRouteId,
                                            Integer resolvedBoardingSeq, Integer resolvedAlightingSeq) {
        return new JourneyRouteOptionDraft(optionIndex, laneIndex, resolvedRouteId, sourceRouteId,
                routeName, wayCode, resolvedBoardingSeq, resolvedAlightingSeq);
    }
}
