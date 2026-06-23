package watoo.grd.nextroute.application.notification.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 버스 알림 구독 요청. 이름은 서버가 보강하므로 받지 않는다. */
@Getter
@NoArgsConstructor
public class BusAlertRequest {
    @NotBlank
    private String stopId;
    @NotBlank
    private String routeId;
}
