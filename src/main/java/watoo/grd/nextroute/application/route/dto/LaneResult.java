package watoo.grd.nextroute.application.route.dto;

public record LaneResult(
        String name,
        String busNo,
        Integer subwayCode,
        Integer type,
        Integer busID,
        String busLocalBlID,
        /** 노선/노선유형의 표시색. 원천 또는 매핑이 없으면 null. */
        String routeDisplayColor,
        /**
         * 이 노선으로 이 구간(같은 승하차 정류장)을 타는 정적 탑승시간(초). 버스 구간에 노선이 여럿 묶이면 노선마다 다르다
         * (bus-lane-selection PR-1; 운영 실측 75% 가 대표와 ±1분, 11% 가 5분 넘게 차이). 구간 {@code sectionTime} 은 대표
         * 노선({@code lanes[0]}) 값을 분으로 반올림한 것이고, 대표 lane 의 이 칸은 반올림 전 초 값이다(605초 구간 = sectionTime 10,
         * rideSeconds 605) — 환승 대기와 알림 소요 추정은 이 초 값을 쓴다. 모르면 null(ODsay, 지하철, 옛 스냅샷) — 그때는 구간 값을 쓴다.
         * 여정 단계의 탑승 소요는 탑승 노선이 정해질 때까지(PR-2) 구간 값(대표 노선) 그대로다.
         */
        Integer rideSeconds
) {
    /** 노선별 탑승시간을 모르는 원천용. null 이 곧 옛 동작이다. */
    public LaneResult(String name, String busNo, Integer subwayCode, Integer type, Integer busID,
                      String busLocalBlID, String routeDisplayColor) {
        this(name, busNo, subwayCode, type, busID, busLocalBlID, routeDisplayColor, null);
    }
}
