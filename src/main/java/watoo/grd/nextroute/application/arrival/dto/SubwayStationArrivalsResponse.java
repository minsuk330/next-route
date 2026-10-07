package watoo.grd.nextroute.application.arrival.dto;

import java.util.List;

/**
 * 역 하나의 곧 오는 열차를 방향별로 묶은 응답.
 *
 * <p>방향 묶기·표시 이름·순서를 서버가 정한다. 앱마다 따로 정하면 같은 역이 화면마다 다른
 * 탭 이름·순서로 보인다.
 */
public record SubwayStationArrivalsResponse(
        String stationId,
        String lineId,
        List<Direction> directions
) {

    /**
     * @param direction 실시간 API 의 {@code updnLine} 원문. 원천이 비워 보내면 {@code null}
     * @param label     표시용. 보통 "잠실 방면", 두 방향 종착이 같으면(2호선) {@code direction} 값
     * @param trains    도착이 빠른 순. 남은 초가 없는 열차는 뒤
     */
    public record Direction(String direction, String label, List<Train> trains) {
    }

    /**
     * @param arrivalSeconds 지금 기준 남은 초. <b>{@code null} 이 흔하다</b> — 원천이 거리로만 답할 때
     * @param stationsAway   조회역까지 남은 정거장 수. 조회역에 있으면 0, 모르면 {@code null}
     */
    public record Train(
            String trainNo,
            String destinationName,
            String trainType,
            Integer arrivalSeconds,
            Integer stationsAway,
            String currentMessage
    ) {
    }
}
