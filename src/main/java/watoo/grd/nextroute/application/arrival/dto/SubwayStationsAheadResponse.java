package watoo.grd.nextroute.application.arrival.dto;

import java.util.List;

/**
 * 승차역부터 한 방향으로 남은 역(#283). 하차역 선택 화면이 세로 노선도로 그린다.
 *
 * @param destinationApplied 받은 행선지를 갈래 선택·자르기에 실제로 썼는지. 못 맞추면 false 로 공통 구간을 준다
 * @param endReason          목록이 어디서 끝났는지
 * @param branches           {@code endReason=BRANCH} 일 때만. 앱은 고른 갈래의 {@code destinationName} 을
 *                           {@code destination} 으로 붙여 다시 부른다
 * @param stations           가까운 순. 승차역이 곧 분기점이면 비어 있고 {@code branches} 만 있다
 */
public record SubwayStationsAheadResponse(
        String lineId,
        String boardStationId,
        String boardStationName,
        String direction,
        boolean destinationApplied,
        EndReason endReason,
        List<Branch> branches,
        List<Station> stations,
        /** 승차역의 OTP GTFS 정류장 id(#298). 단독 추적 시작 {@code boardStopId}. 못 이으면 null */
        String boardGtfsStopId
) {

    public enum EndReason {
        /** 종착(또는 받은 행선지)까지. */
        TERMINAL,
        /** 분기점까지. 그 뒤는 갈래마다 다르다. */
        BRANCH,
        /** 순환선 한 바퀴, 승차역 바로 앞까지. */
        LOOP
    }

    /**
     * @param stationId 실시간 역 ID. 역 마스터에 없는 역이면 null — 그래도 빼지 않는다(빼면 stopsAway 가 틀린다)
     * @param minutes   승차역 출발 기준 예상 분(시간표 근사). 모르면 null
     */
    /**
     * @param gtfsStopId  OTP GTFS 정류장 id(#298) — 단독 추적 시작 {@code alightStopId}
     * @param routeGtfsId 이 역이 나온 OTP 노선 id(#298) — 단독 추적 시작 {@code routeGtfsId}. 갈래 노선은 역마다 다를 수
     *                    있으니 <b>하차역의 값</b>을 쓴다
     */
    public record Station(String stationId, String stationName, int stopsAway, Integer minutes,
                          List<Transfer> transfers, String gtfsStopId, String routeGtfsId) {
    }

    public record Transfer(String lineId, String lineName) {
    }

    /**
     * @param destinationName 다시 부를 때 {@code destination} 값. 보통 그 갈래 끝 역이고, 순환 본선(2호선)이면
     *                        {@code "순환"} 이다 — 한 바퀴 끝 역(신도림이면 대림)을 주면 "대림 방면"이 반대 방향으로 읽힌다
     * @param nextStationName 분기점 다음 역. 갈래를 "문래 방면"처럼 보여 줄 때 쓴다
     * @param kind            {@code LOOP}(순환 본선) 또는 {@code TERMINAL}(종착이 있는 갈래)
     */
    public record Branch(String destinationName, String nextStationName, EndReason kind) {
    }
}
