package watoo.grd.nextroute.application.subway.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

@Getter
@Builder
public class SubwayRealtimeResponse {
    /** ISO-8601 with offset. snapshot이 없으면 null. */
    private String collectedAt;
    /** 스냅샷 수집 후 경과 초. */
    private long ageSeconds;
    private SubwayRealtimeStatus status;
    private List<SubwayRealtimeTrain> trains;
}
