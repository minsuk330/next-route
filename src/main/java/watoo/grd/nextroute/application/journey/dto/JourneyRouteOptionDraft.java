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
 * @param directionKey  실시간 조회 후보를 같은 진행 순서로 묶는 키
 * @param wayCode       ODSAY 지하철 방향 코드. 버스와 방향 미제공 구간은 null
 * @param boardingSeq   내부 버스 노선에서 승차 정류장의 순번. 단건 해석 실패 시 null
 * @param alightingSeq  내부 버스 노선에서 하차 정류장의 순번. 단건 해석 실패 시 null
 * @param routeDisplayColor 검색 lane 의 노선 표시색({@code #RRGGBB}). 지하철은 공식 색표 값(#595). 모르거나 형식이 다르면 null
 * @param searchArrival 저장하지 않는다. 검색 스냅샷의 이 lane 실시간 도착(첫 버스 구간의 조건부 아닌 값만) — 여정 시작 기본 선택(#614)
 * @param rideSeconds   저장하지 않는다. 이 lane 으로 이 구간을 타는 정적 탑승시간(초, {@code LaneResult.rideSeconds}). 모르면 null
 */
public record JourneyRouteOptionDraft(
        int optionIndex,
        int laneIndex,
        String routeId,
        String sourceRouteId,
        String routeName,
        String directionKey,
        Integer wayCode,
        Integer boardingSeq,
        Integer alightingSeq,
        String routeDisplayColor,
        @com.fasterxml.jackson.annotation.JsonIgnore
        watoo.grd.nextroute.application.route.dto.TransferArrival searchArrival,
        @com.fasterxml.jackson.annotation.JsonIgnore
        Integer rideSeconds
) {

    /** 검색 실시간·탑승시간 없는 후보. */
    public JourneyRouteOptionDraft(int optionIndex, int laneIndex, String routeId, String sourceRouteId,
                                   String routeName, String directionKey, Integer wayCode,
                                   Integer boardingSeq, Integer alightingSeq, String routeDisplayColor) {
        this(optionIndex, laneIndex, routeId, sourceRouteId, routeName, directionKey, wayCode,
                boardingSeq, alightingSeq, routeDisplayColor, null, null);
    }

    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9A-Fa-f]{6}$");

    /** 색 없는 후보(옛 호출부·시험용). */
    public JourneyRouteOptionDraft(int optionIndex, int laneIndex, String routeId, String sourceRouteId,
                                   String routeName, String directionKey, Integer wayCode,
                                   Integer boardingSeq, Integer alightingSeq) {
        this(optionIndex, laneIndex, routeId, sourceRouteId, routeName, directionKey, wayCode,
                boardingSeq, alightingSeq, null);
    }

    /**
     * 원천 식별자만 채운 후보를 만든다.
     *
     * <p>{@code routeId} 를 비워 두는 것은 의도적이다. 원천 식별자를 내부 노선으로 해석하려면
     * 저장소를 뒤져야 하는데, 이 계산기는 아무것도 조회하지 않는 순수 변환으로 남긴다.
     * 해석은 실시간 대조가 실제로 필요해지는 단계의 몫이다.
     */
    public static JourneyRouteOptionDraft of(int index, String sourceRouteId, String routeName,
                                             Integer wayCode, String routeDisplayColor) {
        return new JourneyRouteOptionDraft(index, index, null, sourceRouteId, routeName, null,
                wayCode, null, null, displayColor(routeDisplayColor));
    }

    /** 검색 스냅샷의 이 lane 실시간 도착과 탑승시간을 싣는다(저장하지 않는 값). */
    public JourneyRouteOptionDraft withSearch(watoo.grd.nextroute.application.route.dto.TransferArrival arrival,
                                              Integer laneRideSeconds) {
        return new JourneyRouteOptionDraft(optionIndex, laneIndex, routeId, sourceRouteId, routeName, directionKey,
                wayCode, boardingSeq, alightingSeq, routeDisplayColor, arrival, laneRideSeconds);
    }

    /**
     * 여정 시작의 기본 선택 후보 순번(#614): {@code startedAt} 에 출발해 걸어서 닿은 뒤 오는 차 중 가장 먼저 오는 lane.
     * 검색 bestLane 과 같은 기준({@link watoo.grd.nextroute.application.route.dto.TransferArrival#isBoardable()})에 도보 도달을 더했다.
     * 동률은 앞 순번. 그런 lane 이 없으면(실시간 없음, 조건부 구간, 전부 지나감) 첫 후보 — 시간표 대표 노선.
     */
    public static int defaultIndex(java.util.List<JourneyRouteOptionDraft> options, java.time.Instant startedAt) {
        if (options == null || options.isEmpty()) {
            return 0;
        }
        return options.stream()
                .filter(o -> o.searchArrival() != null && o.searchArrival().reachableIfStartedAt(startedAt))
                .min(java.util.Comparator.comparing((JourneyRouteOptionDraft o) -> o.searchArrival().predictedArrivalAt())
                        .thenComparingInt(JourneyRouteOptionDraft::optionIndex))
                .map(JourneyRouteOptionDraft::optionIndex)
                .orElse(options.get(0).optionIndex());
    }

    /** {@code #RRGGBB} 만 받는다(대문자로). 다른 모양은 저장하지 않고 null — 앱이 대체색을 쓴다. */
    static String displayColor(String color) {
        if (color == null || !HEX_COLOR.matcher(color.trim()).matches()) {
            return null;
        }
        return color.trim().toUpperCase(java.util.Locale.ROOT);
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
                routeName, directionKey(wayCode, resolvedBoardingSeq, resolvedAlightingSeq),
                wayCode, resolvedBoardingSeq, resolvedAlightingSeq, routeDisplayColor, searchArrival, rideSeconds);
    }

    private static String directionKey(Integer wayCode, Integer boardingSeq, Integer alightingSeq) {
        if (boardingSeq != null && alightingSeq != null) {
            return "SEQ:" + boardingSeq + ">" + alightingSeq;
        }
        return wayCode == null ? null : "WAY:" + wayCode;
    }
}
