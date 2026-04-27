package watoo.grd.nextroute.application.subway.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SubwayRealtimeTrain {
    private String trainNo;
    private String lineId;
    private String direction;
    private String stationName;
    private String prevStationName;
    private String nextStationName;
    private Integer arrivalSeconds;
    /** Seoul API recptnDt. 포맷: "yyyy-MM-dd HH:mm:ss" */
    private String receivedAt;
    /** prevStation → stationName 구간 소요시간(초). DB에 없으면 null. */
    private Double segmentTravelSeconds;
    /** stationName → nextStation 구간 소요시간(초). arrivalCode=2 보간용. */
    private Double nextSegmentTravelSeconds;
    /** 0=도착전, 1=도착, 2=출발 */
    private String arrivalCode;
    private String currentMessage;
    private String destinationName;
    private String trainType;
}
