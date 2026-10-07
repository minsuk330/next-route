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

    /** 좌표 주변 정류장/역. {@code gtfsStopId} 가 이후 모든 호출의 키다. */
    public record Stop(String gtfsStopId, String name, double lat, double lon, RoutingMode mode) {
    }

    /**
     * 그 정류장에서 탈 수 있는 노선의 한 방향.
     *
     * @param directionLabel headsign, 순환 지하철이면 내선·외선, 그 밖은 "{종점} 방면"
     * @param patternCode    하차 후보 조회·시작에 힌트로 넘긴다. 그래프가 바뀌면 낡을 수 있고, 그래도 서버가 노선으로 다시 찾는다
     * @param color          "#" 붙은 표시색. 없으면 null
     */
    public record Direction(String routeGtfsId, RoutingMode mode, String routeName, String color,
                            String directionLabel, String terminalName, String patternCode) {
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
