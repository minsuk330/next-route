package watoo.grd.nextroute.application.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 토스 메신저(기능성 메시지) 설정. 알림 종류별 send-message 템플릿 세트 코드. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "toss.messenger")
public class TossMessengerProperties {

    /** 버스 도착 알림 템플릿 세트 코드. */
    private String busArrivalTemplateSetCode;

    /** 목표 도착 알림 — 출발 리마인드(DEPARTURE) 템플릿 세트 코드. */
    private String routeDepartureTemplateSetCode;

    /** 목표 도착 알림 — 변경(CHANGE) 템플릿 세트 코드. */
    private String routeChangeTemplateSetCode;
}
