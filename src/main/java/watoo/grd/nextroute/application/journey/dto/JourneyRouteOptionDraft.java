package watoo.grd.nextroute.application.journey.dto;

/**
 * 한 구간에서 탈 수 있는 노선 후보 하나. 아직 저장되지 않은 계획 값이다.
 *
 * <p>검색 응답의 {@code lanes} 한 칸이 여기 한 개가 된다. 응답이 준 순서를 {@code laneIndex} 로
 * 그대로 들고 있어서, 나중에 후보를 걸러 내더라도 "원본에서 몇 번째였나"를 잃지 않는다.
 *
 * @param optionIndex   이 단계 안에서의 후보 순번. 0 부터
 * @param laneIndex     검색 응답 {@code lanes} 배열에서의 원래 순번
 * @param routeId       내부 노선 식별자. <b>이 단계에서는 항상 null</b> — 아래 설명 참고
 * @param sourceRouteId 검색 응답이 준 원천 식별자(버스는 {@code busLocalBlID}, 지하철은 노선 코드)
 * @param routeName     사람이 읽는 이름(버스 번호, 노선명)
 */
public record JourneyRouteOptionDraft(
        int optionIndex,
        int laneIndex,
        String routeId,
        String sourceRouteId,
        String routeName
) {

    /**
     * 원천 식별자만 채운 후보를 만든다.
     *
     * <p>{@code routeId} 를 비워 두는 것은 의도적이다. 원천 식별자를 내부 노선으로 해석하려면
     * 저장소를 뒤져야 하는데, 이 계산기는 아무것도 조회하지 않는 순수 변환으로 남긴다.
     * 해석은 실시간 대조가 실제로 필요해지는 단계의 몫이다.
     */
    public static JourneyRouteOptionDraft of(int index, String sourceRouteId, String routeName) {
        return new JourneyRouteOptionDraft(index, index, null, sourceRouteId, routeName);
    }

    /** 실시간 대조에 쓸 수 있는 식별자가 있는지. 없으면 근거 품질이 "모름"이 된다. */
    public boolean hasIdentifier() {
        return sourceRouteId != null && !sourceRouteId.isBlank();
    }
}
