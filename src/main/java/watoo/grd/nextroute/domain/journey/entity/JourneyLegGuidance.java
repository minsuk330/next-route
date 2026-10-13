package watoo.grd.nextroute.domain.journey.entity;

/**
 * 안내 시트의 지점 줄에 붙는 계획 값(#266). 여정을 만들 때 한 번 정해지고 바뀌지 않는다.
 *
 * <p>전부 없을 수 있다 — 없으면 앱이 그 줄을 숨긴다. 추정값을 지어내지 않는다.
 *
 * @param startStopNo      버스 승차 정류장 번호(표지판의 5자리 ARS 번호 등). 대기 단계는 승차 정류장
 * @param endStopNo        버스 하차 정류장 번호
 * @param directionName    지하철 진행 방면 — 승차역 다음 역 이름. 앱이 "{이름} 방면"으로 보여 준다
 * @param startExitNo      지하철 승차역에 들어가는 출구 번호
 * @param endExitNo        지하철 하차역에서 나가는 출구 번호
 * @param fastAlightJson   빠른 하차 칸·문 목록 JSON({@code [{"carDoor":"2-3","facility":"STAIRS"}]})
 * @param alightDoorSide   지하철 하차역에서 내리는 문 방향 {@code LEFT}·{@code RIGHT}(#335)
 */
public record JourneyLegGuidance(
        String startStopNo,
        String endStopNo,
        String directionName,
        String startExitNo,
        String endExitNo,
        String fastAlightJson,
        String alightDoorSide
) {

    public static final JourneyLegGuidance EMPTY = new JourneyLegGuidance(null, null, null, null, null, null, null);

    /** 내리는 문 방향(#335) 이전 값 묶음. */
    public JourneyLegGuidance(String startStopNo, String endStopNo, String directionName,
                              String startExitNo, String endExitNo, String fastAlightJson) {
        this(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson, null);
    }

    public static JourneyLegGuidance orEmpty(JourneyLegGuidance guidance) {
        return guidance == null ? EMPTY : guidance;
    }

    public JourneyLegGuidance withStopNos(String startStopNo, String endStopNo) {
        return new JourneyLegGuidance(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson,
                alightDoorSide);
    }

    public JourneyLegGuidance withExits(String startExitNo, String endExitNo) {
        return new JourneyLegGuidance(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson,
                alightDoorSide);
    }

    public JourneyLegGuidance withDirectionName(String directionName) {
        return new JourneyLegGuidance(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson,
                alightDoorSide);
    }

    public JourneyLegGuidance withFastAlightJson(String fastAlightJson) {
        return new JourneyLegGuidance(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson,
                alightDoorSide);
    }

    public JourneyLegGuidance withAlightDoorSide(String alightDoorSide) {
        return new JourneyLegGuidance(startStopNo, endStopNo, directionName, startExitNo, endExitNo, fastAlightJson,
                alightDoorSide);
    }

    /** 하차점이 바뀐 단계(재탐색 자르기)가 새 하차점의 값으로 갈아 끼운다. 승차 쪽은 그대로다. */
    public JourneyLegGuidance withEndFrom(JourneyLegGuidance other) {
        JourneyLegGuidance o = orEmpty(other);
        return new JourneyLegGuidance(startStopNo, o.endStopNo, directionName, startExitNo, o.endExitNo,
                o.fastAlightJson, o.alightDoorSide);
    }
}
