package watoo.grd.nextroute.application.subway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubwayRealtimeSnapshot {
    /** ISO-8601 with offset. 예: "2026-03-10T14:23:15+09:00" */
    private String collectedAt;
    private SubwayRealtimeStatus status;
    private List<SubwayRealtimeTrain> trains;
}
