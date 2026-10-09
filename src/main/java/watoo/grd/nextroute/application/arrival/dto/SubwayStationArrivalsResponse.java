package watoo.grd.nextroute.application.arrival.dto;

import java.time.OffsetDateTime;
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
     * @param departed  이 역을 떠난 열차, 최근 순, 최대 3대·10분 이내. 위치 수집이 없는 노선은 늘 비어 있다
     * @param stationsBehind    노선도용(#317): 이 방향 열차가 오는 쪽 역, <b>먼 역 → 가까운 역</b>(마지막이 직전 역), 최대 4.
     *                          모르면 빈 목록
     * @param nextStation       이 역 다음 역. 종착·분기점·모름이면 null
     * @param boardDwellSeconds 이 역 정차 계획 초. 모르면 null
     * @param behindEnd         {@code stationsBehind} 가 왜 거기서 끝났나
     */
    public record Direction(String direction, String label, List<Train> trains, List<Departed> departed,
                            List<StripStation> stationsBehind, StripStation nextStation,
                            Integer boardDwellSeconds, BehindEnd behindEnd) {

        public Direction(String direction, String label, List<Train> trains, List<Departed> departed) {
            this(direction, label, trains, departed, List.of(), null, null, BehindEnd.UNKNOWN);
        }

        public Direction withStrip(LineStrip strip) {
            return new Direction(direction, label, trains, departed, strip.stationsBehind(), strip.nextStation(),
                    strip.boardDwellSeconds(), strip.behindEnd());
        }
    }

    /**
     * 노선도 한 칸(#317·#318). 초는 전부 GTFS <b>계획</b>(대표 패턴) 값이다.
     *
     * @param stationId     역 마스터에 없는 역이면 null
     * @param secondsToHere stationsBehind: 이 역 출발 → 조회역 도착. nextStation: 조회역 출발 → 이 역 도착
     * @param runSeconds    진행 순서로 직전 역 출발 → 이 역 도착. 직전 역→조회역 운행 초는 stationsBehind 마지막 역의
     *                      {@code secondsToHere} 다
     * @param dwellSeconds  이 역 정차
     */
    public record StripStation(String stationId, String stationName, Integer secondsToHere, Integer runSeconds,
                               Integer dwellSeconds) {
    }

    public enum BehindEnd {
        /** 4역을 다 채웠다. */
        FULL,
        /** 기점이라 짧다. */
        ORIGIN,
        /** 그 앞은 갈래마다 달라 합류점에서 끊었다. 목록 밖 열차는 '더 먼 곳'에 둔다. */
        BRANCH,
        /** 노선 패턴을 아직 모른다(캐시 미스·실패). */
        UNKNOWN
    }

    /** 한 방향 노선도. {@link watoo.grd.nextroute.application.arrival.port.in.GetSubwayLineStripUseCase} 의 답. */
    public record LineStrip(List<StripStation> stationsBehind, StripStation nextStation, Integer boardDwellSeconds,
                            BehindEnd behindEnd) {
    }

    /**
     * 이 역을 방금 떠난 열차(#299). 사용자는 대개 타고 나서 앱을 연다 — 자기 열차는 이미 떠난 쪽에 있다.
     *
     * @param trainNo            열차 선택(#293)에 그대로 쓰는 번호
     * @param departedAt         떠난 시각 추정(관측 구간의 가운데). 위치 원천 해상도가 60~150초라 그만큼 오차가 있다
     * @param currentStationName 지금 위치. 떠난 뒤 관측이 없으면 null
     */
    public record Departed(String trainNo, String destinationName, String trainType,
                           OffsetDateTime departedAt, String currentStationName) {
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
