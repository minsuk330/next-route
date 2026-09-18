package watoo.grd.nextroute.application.route.dto;

public record LaneResult(
        String name,
        String busNo,
        Integer subwayCode,
        Integer type,
        Integer busID,
        String busLocalBlID,
        /** 노선/노선유형의 표시색. 원천 또는 매핑이 없으면 null. */
        String routeDisplayColor
) {
}
