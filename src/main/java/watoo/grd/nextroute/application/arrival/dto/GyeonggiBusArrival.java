package watoo.grd.nextroute.application.arrival.dto;

/**
 * 경기도 버스도착 API(GBIS v2 {@code getBusArrivalItemv2}) 한 노선의 도착 정보. 원천 단위를 그대로 둔다.
 * 서울 응답 의미로의 변환(분→초, 메시지, 혼잡도 코드)은 {@code GyeonggiArrivalMapper} 가 한다.
 *
 * <p>원천은 값이 없으면 {@code ""} 를 주므로 모든 수치는 nullable.
 *
 * @param predictMin1 첫 번째 버스 도착 예정(분)
 * @param locationNo1 첫 번째 버스가 몇 정류장 전인지
 * @param crowded1    혼잡도 1 여유 · 2 보통 · 3 혼잡 · 4 매우혼잡
 * @param remainSeat1 남은 좌석(좌석버스만 의미, 그 외 -1 등)
 * @param flag        노선 운행 상태(원천 값 그대로, 예 {@code PASS})
 */
public record GyeonggiBusArrival(
        String routeId,
        Integer predictMin1,
        Integer locationNo1,
        Integer crowded1,
        Integer remainSeat1,
        Integer predictMin2,
        Integer locationNo2,
        Integer crowded2,
        Integer remainSeat2,
        String flag
) {
}
