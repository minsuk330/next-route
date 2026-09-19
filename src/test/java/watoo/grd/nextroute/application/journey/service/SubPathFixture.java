package watoo.grd.nextroute.application.journey.service;

import watoo.grd.nextroute.application.route.dto.CoordPoint;
import watoo.grd.nextroute.application.route.dto.LaneResult;
import watoo.grd.nextroute.application.route.dto.StationResult;
import watoo.grd.nextroute.application.route.dto.SubPathResult;
import watoo.grd.nextroute.application.route.dto.WalkStep;

import java.util.List;

/**
 * 검색 결과 구간을 테스트에서 손으로 만들기 위한 도구.
 *
 * <p>{@link SubPathResult} 는 필드가 서른 개가 넘고 대부분 이 테스트와 무관하다. 여기서
 * 쪼개기에 실제로 쓰이는 값만 이름 있는 인자로 받고 나머지는 null 로 채운다.
 */
public final class SubPathFixture {

    private SubPathFixture() {
    }

    public static Builder walk(int minutes) {
        return new Builder(3, minutes);
    }

    public static Builder bus(int minutes) {
        return new Builder(2, minutes);
    }

    public static Builder subway(int minutes) {
        return new Builder(1, minutes);
    }

    public static LaneResult busLane(String busNo, String localBlId) {
        return new LaneResult(busNo, busNo, null, null, null, localBlId, null);
    }

    public static LaneResult namelessLane() {
        return new LaneResult(null, null, null, null, null, null, null);
    }

    public static LaneResult subwayLane(String name, Integer subwayCode) {
        return new LaneResult(name, null, subwayCode, null, null, null, null);
    }

    public static final class Builder {

        private final int trafficType;
        private final int sectionTime;

        private Double distance;
        private Integer payment;
        private List<LaneResult> lanes;
        private List<StationResult> stations;
        private String startName;
        private String endName;
        private Double startX;
        private Double startY;
        private Double endX;
        private Double endY;
        private Integer startId;
        private Integer wayCode;
        private String startLocalStationID;
        private String endLocalStationID;
        private String startArsID;
        private String endArsID;
        private Integer endID;
        private Integer walkTotalTimeSeconds;
        private List<CoordPoint> polyline;
        private List<WalkStep> walkSteps;

        private Builder(int trafficType, int sectionTime) {
            this.trafficType = trafficType;
            this.sectionTime = sectionTime;
        }

        public Builder names(String start, String end) {
            this.startName = start;
            this.endName = end;
            return this;
        }

        public Builder coords(double startX, double startY, double endX, double endY) {
            this.startX = startX;
            this.startY = startY;
            this.endX = endX;
            this.endY = endY;
            return this;
        }

        /** 버스 구간 식별자. */
        public Builder busStops(String start, String end) {
            this.startLocalStationID = start;
            this.endLocalStationID = end;
            return this;
        }

        public Builder ars(String start, String end) {
            this.startArsID = start;
            this.endArsID = end;
            return this;
        }

        /** 지하철 구간 식별자. */
        public Builder subwayStations(Integer start, Integer end) {
            this.startId = start;
            this.endID = end;
            return this;
        }

        public Builder wayCode(Integer wayCode) {
            this.wayCode = wayCode;
            return this;
        }

        public Builder lanes(LaneResult... lanes) {
            this.lanes = List.of(lanes);
            return this;
        }

        public Builder stations(StationResult... stations) {
            this.stations = List.of(stations);
            return this;
        }

        public Builder distance(Double meters) {
            this.distance = meters;
            return this;
        }

        public Builder payment(Integer won) {
            this.payment = won;
            return this;
        }

        public Builder walkSeconds(Integer seconds) {
            this.walkTotalTimeSeconds = seconds;
            return this;
        }

        /** 지하철·도보 구간 좌표. 검색 결과의 차량/도보 공용 폴리라인 슬롯에 들어간다. */
        public Builder polyline(CoordPoint... points) {
            this.polyline = List.of(points);
            return this;
        }

        /** TMAP 도보 안내점. 폴리라인 정점과 좌표가 같아야 실제 응답과 같은 모양이다. */
        public Builder walkSteps(WalkStep... steps) {
            this.walkSteps = List.of(steps);
            return this;
        }

        public SubPathResult build() {
            return new SubPathResult(
                    trafficType, sectionTime, distance, lanes, stations,
                    startName, endName, startX, startY, endX, endY,
                    null, payment, startId, null, wayCode, polyline,
                    null, null, null, null, null, null, walkSteps,
                    startLocalStationID, endLocalStationID, startArsID, endArsID, endID,
                    walkTotalTimeSeconds, null);
        }
    }
}
