package watoo.grd.nextroute.application.route.port.out;

import watoo.grd.nextroute.application.route.dto.WalkSegment;

public interface TmapPedestrianPort {

    /**
     * TMAP 보행자 경로 안내 호출.
     *
     * @return TMAP이 정상 응답하면 polyline을 포함한 WalkSegment.
     *         서비스 지역 외/빈 features는 {@link WalkSegment#empty()} 반환.
     * @throws watoo.grd.nextroute.application.route.exception.TmapApiException
     *         5xx, 타임아웃 등 호출 자체가 실패한 경우.
     */
    WalkSegment search(WalkSearchCommand cmd);

    record WalkSearchCommand(
            double startX,
            double startY,
            double endX,
            double endY,
            String startName,
            String endName,
            int searchOption
    ) {
        public WalkSearchCommand(double startX, double startY, double endX, double endY,
                                 String startName, String endName) {
            this(startX, startY, endX, endY, startName, endName, 0);
        }
    }
}
