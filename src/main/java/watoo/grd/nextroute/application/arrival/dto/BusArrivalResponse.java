package watoo.grd.nextroute.application.arrival.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BusArrivalResponse {
    private String routeId;
    private String arrivalMsg1;
    private Integer predictTime1;
    private Integer congestionNum1;
    private String arrivalMsg2;
    private Integer predictTime2;
    private Integer congestionNum2;
}
