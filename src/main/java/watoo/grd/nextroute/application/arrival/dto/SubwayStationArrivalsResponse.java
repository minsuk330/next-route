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
     * @param departed  이 역을 떠난 열차, 최근 순, 최대 3대·10분 이내. 위치 수집이 없는 노선은 도착 API 로 채운다(#317)
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
     * @param source             출발을 안 원천(#317). 둘 다 열차 선택에 쓸 수 있다 — 정확도만 다르다
     */
    public record Departed(String trainNo, String destinationName, String trainType,
                           OffsetDateTime departedAt, String currentStationName, DepartureSource source) {

        public Departed(String trainNo, String destinationName, String trainType,
                        OffsetDateTime departedAt, String currentStationName) {
            this(trainNo, destinationName, trainType, departedAt, currentStationName, DepartureSource.POSITION);
        }
    }

    public enum DepartureSource {
        /** 위치 API 출발 사건. 출발 시각 오차 60~150초. */
        POSITION,
        /** 도착 API 로 본 출발(위치 API 가 없는 노선). 수집 주기(15초) 해상도지만 원천 지연이 있다. */
        ARRIVAL
    }

    /**
     * @param arrivalSeconds     지금 기준 남은 초. <b>{@code null} 이 흔하다</b> — 원천이 거리로만 답할 때
     * @param stationsAway       조회역까지 남은 정거장 수. 조회역에 있으면 0, 모르면 {@code null}
     * @param currentStationId   열차가 지금 있는(또는 막 떠난) 역(#317). 모르면 null. 이름만 알면 이것만 null 일 수 있다
     * @param currentStationName 위 역 이름
     * @param status             그 역 기준 상태. {@code DEPARTED} 면 그 역을 떠나 이 방향 다음 역으로 가는 중이다. 모르면 null
     * @param observedAt         그 위치·상태가 <b>확인된</b> 원천 시각. 사건(진입·출발) 시각이 아니다 — 원천에 따라 상태가 그대로여도
     *                           계속 갱신된다. 보간 앵커로 쓰지 말 것
     * @param positionSource     위치 원천. {@code POSITION}(위치 API) 이 {@code ARRIVAL}(도착 API 의 역 정보)보다 정확하다
     * @param segment            달리는 중인 구간과 그 출발·도착 예정(#317·#318). <b>노선도 보간의 앵커</b>. 정차·모름이면 null
     */
    public record Train(
            String trainNo,
            String destinationName,
            String trainType,
            Integer arrivalSeconds,
            Integer stationsAway,
            String currentMessage,
            String currentStationId,
            String currentStationName,
            TrainStatus status,
            OffsetDateTime observedAt,
            PositionSource positionSource,
            Segment segment
    ) {
    }

    /**
     * 열차가 지금 달리는 구간. 앱은 {@code departedAt → expectedAt} 사이를 선형으로 움직인다(역행 금지는 앱 몫).
     *
     * @param departedAt 출발 역을 떠난 시각. 도착 API "전역 출발"을 처음 본 시각(#316 기록), 없으면 위치 API 상태가 바뀐 시각
     * @param expectedAt 도착 예정. {@code now} 보다 과거일 수 있다(늦는 중). 모르면 null
     * @param source     {@code expectedAt} 원천. {@code MEASURED} = 오늘 같은 구간을 지난 최근 열차 실측 중앙값,
     *                   {@code SCHEDULED} = GTFS 계획(실측이 없을 때만). {@code expectedAt} 이 null 이면 null
     */
    public record Segment(String fromStationId, String fromStationName, String toStationId, String toStationName,
                          OffsetDateTime departedAt, OffsetDateTime expectedAt, SegmentSource source) {
    }

    public enum SegmentSource { MEASURED, SCHEDULED }

    /** 열차가 {@code currentStation} 기준 어디 있나. 원천 코드(arvlCd·trainSttus)를 세 가지로 접었다. */
    public enum TrainStatus {
        /** 그 역에 들어오는 중. */
        APPROACHING,
        /** 그 역에 서 있다. */
        ARRIVED,
        /** 그 역을 떠나 다음 역으로 가는 중. */
        DEPARTED
    }

    public enum PositionSource {
        /** 서울 실시간 위치 API(노선 단위). */
        POSITION,
        /** 서울 실시간 도착 API 가 함께 주는 역 정보. 위치 API 가 없는 노선(수인분당·경의중앙 등)의 대체다. */
        ARRIVAL
    }
}
