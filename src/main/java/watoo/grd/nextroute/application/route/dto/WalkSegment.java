package watoo.grd.nextroute.application.route.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.Collections;
import java.util.List;

/**
 * TMAP 보행자 경로 안내 응답을 정규화한 도보 세그먼트.
 * 빈 세그먼트는 서비스 지역 외 또는 응답 features 부재를 의미한다.
 */
public record WalkSegment(
        List<CoordPoint> polyline,
        int totalDistance,
        int totalTime,
        List<WalkStep> steps
) {
    public static WalkSegment empty() {
        return new WalkSegment(Collections.emptyList(), 0, 0, Collections.emptyList());
    }

    @JsonIgnore
    public boolean isEmpty() {
        return polyline == null || polyline.isEmpty();
    }
}
