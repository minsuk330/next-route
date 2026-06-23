package watoo.grd.nextroute.application.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** 버스 알림 구독 요청. 이름은 서버가 보강하므로 받지 않는다. */
@Getter
@NoArgsConstructor
public class BusAlertRequest {
    @NotBlank
    private String stopId;
    @NotBlank
    private String routeId;
    /** 사용자 정류소 예상 도착 시각. 이 시각 이후 오는 버스를 알림 대상으로 한다. */
    @NotNull
    private LocalDateTime userEta;
}
