package watoo.grd.nextroute.application.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 토스 메신저(기능성 메시지) 설정. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "toss.messenger")
public class TossMessengerProperties {

    /** 서버 send-message 용 템플릿 세트 코드. */
    private String busArrivalTemplateSetCode;

    /** 앱 requestNotificationAgreement 참조용 동의 템플릿 코드(서버 미사용, 클라 전달용). */
    private String agreementTemplateCode;
}
