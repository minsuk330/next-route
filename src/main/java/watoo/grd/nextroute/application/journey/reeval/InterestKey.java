package watoo.grd.nextroute.application.journey.reeval;

/**
 * 여정이 지켜보는 실시간 데이터 한 칸.
 *
 * <p>평가기가 실제로 읽는 단위와 같아야 한다. 더 굵으면 상관없는 변화로 깨우고, 더 잘면 같은 변화를
 * 놓친다.
 */
public sealed interface InterestKey {

    /**
     * 지하철: 노선·역·방향. 평가기는 이 셋으로 열차를 고른다({@code SubwayRealtimeArrivalReader}).
     *
     * @param direction {@code SubwayDirectionNormalizer} 가 정한 방향 문자열(상행·하행·내선·외선)
     */
    record Subway(String lineId, String stationId, String direction) implements InterestKey {
    }

    /** 버스: 정류장·노선·순번. 평가기의 도착 캐시 키와 같다({@code bus:search:arr:{stop}:{route}:{seq}}). */
    record Bus(String stopId, String routeId, Integer seq) implements InterestKey {
    }
}
