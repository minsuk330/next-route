package watoo.grd.nextroute.application.arrival.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class SubwayArrivalResponse {
    private String lineId;
    private String direction;
    private Integer arrivalSeconds;
    private String currentMessage;
    private String destinationName;
    private String trainType;
    private String arrivalCode;
}
