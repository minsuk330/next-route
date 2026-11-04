package watoo.grd.nextroute.application.journey.dto;

import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.routing.RoutingMode;

import java.util.List;

/**
 * 단독 추적(#265) 선택 화면 응답. 정류장 → 방향 → 하차 후보 순서로 고른다.
 */
public final class TrackViews {

    private TrackViews() {
    }

    /**
     * 좌표 주변 정류장/역. {@code gtfsStopId} 가 이후 모든 호출의 키다.
     *
     * <p>피드별로 쪼개진 같은 버스 정류장(서울·경기·인천)은 한 줄이다. 대표는 서울 → 경기 → 인천 순이고, 노선 조회는
     * 묶음 전체 노선을 준다.
     *
     * <p>실시간 조회 id(#313)는 못 풀면 null 이다 — 앱은 그때 도착 정보 없이 노선 선택·추적만 보여 준다.
     * 지하철 정류장을 지나는 호선이 둘 이상이면 호선마다 한 줄이다.
     *
     * @param arrivalStopId    버스 도착 조회 정류장 id(서울 9자리 / 경기 GBIS 9자리 / 그 밖 TAGO 접두사 포함)
     * @param arsId            서울 정류장 번호. 서울 밖이면 null
     * @param arrivalStationId 지하철 역 도착 조회 id(서울 실시간 {@code statn_id})
     * @param routeNames       지나는 노선 이름(묶음 합집합, 중복 제거)
     * @param heading          버스 대표 진행 방향(#538). 지하철·못 구함·계산 상한 밖이면 null
     * @param routes           버스 노선 칩(#542). {@code routeNames} 와 같은 범위. 지하철이면 null
     */
    public record Stop(String gtfsStopId, String name, double lat, double lon, RoutingMode mode,
                       Integer distanceMeters, String arrivalStopId, String arsId,
                       String arrivalStationId, String lineId, String lineName,
                       List<String> routeNames, Heading heading, List<Route> routes) {
    }

    /**
     * 근처 목록의 노선 칩(#542).
     *
     * @param color {@link Direction#color} 와 같은 값. 모르면 null
     */
    public record Route(String name, String color) {
    }

    /**
     * 정류장의 대표 진행 방향(#538). 앱은 근처 정류장을 이것으로 방향별 섹션으로 묶는다.
     *
     * <p>서울 버스 패턴은 차고지 → 차고지 왕복 하나라, 같은 패턴 코드가 길 건너편 정류장에도 나온다. 그래서 앱은
     * {@code patternCodes} 가 겹치고 {@code bearingDegrees} 차이가 60° 이하일 때만 같은 섹션으로 묶는다.
     *
     * @param patternCodes   대표 방향으로 이 정류장(형제 묶음 포함)을 지나는 패턴 코드 전부, 사전순
     * @param nextStopNames  대표 방향의 다음 정류장 1~2개
     * @param bearingDegrees 이 정류장 → {@code nextStopNames[0]} 방위각(북 0, 시계방향, 0~360). 사용자 위치와 무관.
     *                       좌표 없으면 null
     */
    public record Heading(List<String> patternCodes, List<String> nextStopNames, Double bearingDegrees) {
    }

    /**
     * 그 정류장에서 탈 수 있는 노선의 한 방향.
     *
     * @param directionLabel headsign, 순환 지하철이면 내선·외선, 그 밖은 "{종점} 방면"
     * @param patternCode    하차 후보 조회·시작에 힌트로 넘긴다. 그래프가 바뀌면 낡을 수 있고, 그래도 서버가 노선으로 다시 찾는다
     * @param color          "#" 붙은 표시색. 없으면 null
     * @param arrivalRouteId 버스 도착 조회 노선 id(#313). 지하철·못 풀면 null
     * @param boardStopGtfsId 이 노선을 실제로 타는 정류장. 고른 정류장이거나, 피드별로 쪼개진 같은 정류장
     *                        ({@code TrackStopSiblings}) 중 하나다. 하차 후보·노선 선·시작의 {@code from} 으로 넘긴다
     * @param arrivalStopId   이 노선의 버스 도착 조회 정류장 id — {@code boardStopGtfsId} 기준. 경기 노선은 경기 정류장
     *                        id 로 물어야 나온다. 지하철·못 풀면 null
     * @param nextStopNames   승차 다음 정류장 1~2개(#538). 없으면 빈 목록
     */
    public record Direction(String routeGtfsId, RoutingMode mode, String routeName, String color,
                            String directionLabel, String terminalName, String patternCode,
                            String arrivalRouteId, String boardStopGtfsId, String arrivalStopId,
                            List<String> nextStopNames) {
    }

    /** 하차 후보. {@code index} 는 승차 다음 정류장이 0 이다. */
    public record AlightStop(String gtfsStopId, String name, double lat, double lon, int index) {
    }

    /**
     * 하차 선택 지도의 노선 선(#312). 추적 시작 후 안내 선과 같은 패턴 형상 slice 다.
     *
     * @param points  {@code x}=경도, {@code y}=위도. 승차 정류장부터 하차(또는 한 바퀴 끝)까지
     * @param quality {@code SHAPE_OK}=GTFS 형상, {@code STOP_LINE}=형상이 없어 정류장을 이은 직선
     */
    public record Shape(List<CoordPoint> points, ShapeQuality quality) {
    }

    public enum ShapeQuality {
        SHAPE_OK,
        STOP_LINE
    }
}
