package watoo.grd.nextroute.application.arrival.dto;

import lombok.Builder;
import lombok.Getter;

import java.util.List;

/**
 * 버스 도착 정보(서울 응답 의미로 통일). 뒤에 붙은 칸(bus-boarding-flow S1)은 앱의 대기 화면이 쓴다 — 전부 모르면 null.
 *
 * <ul>
 *   <li>{@code stopsAway}: 그 버스가 몇 정류장 전인지. 0 = 이 정류장에 와 있거나 바로 앞 구간.</li>
 *   <li>{@code vehicleId}: 공급자 차량 id. 첫 차가 바뀌었는지(=앞 차가 지나갔는지) 앱이 가릴 때 쓴다.</li>
 *   <li>{@code approachStops1}: 첫 차의 현재 정류장부터 이 정류장 바로 앞까지의 이름(순서대로). {@code stopsAway1} 이 3 이하면
 *       전부, 넘으면 처음 1개 + 마지막 2개 — 앱은 {@code stopsAway1 > size} 로 가운데가 줄었음을 안다.</li>
 * </ul>
 */
@Getter
@Builder(toBuilder = true)
public class BusArrivalResponse {
    private String routeId;
    private String arrivalMsg1;
    private Integer predictTime1;
    private Integer congestionNum1;
    private String arrivalMsg2;
    private Integer predictTime2;
    private Integer congestionNum2;
    private Integer stopsAway1;
    private Integer stopsAway2;
    private String vehicleId1;
    private String vehicleId2;
    private List<String> approachStops1;
}
