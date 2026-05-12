package watoo.grd.nextroute.application.route.dto;

/**
 * TMAP 보행자 경로 안내의 turn-by-turn 단계.
 *
 * @param index       경로 순번
 * @param pointType   SP(출발), EP(도착), PP(경유), GP(일반 안내점)
 * @param turnType    회전 정보 코드 (TMAP 문서 참조)
 * @param description "보행자도로 을 따라 99m 이동" 등 안내 텍스트
 * @param x           경도 (lng)
 * @param y           위도 (lat)
 */
public record WalkStep(
        int index,
        String pointType,
        Integer turnType,
        String description,
        double x,
        double y
) {}
