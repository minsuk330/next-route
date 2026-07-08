package watoo.grd.nextroute.application.auth.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * /api/admin/** 보호용 정적 API 키. ops/cron 수동 트리거가 X-Admin-Key 헤더로 인증한다.
 * 기본 공백(fail-closed): 키를 주입하지 않으면 admin 엔드포인트는 전부 401.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "auth.admin")
public class AdminProperties {

    /** X-Admin-Key 헤더와 대조할 시크릿. 공백이면 admin 인증 자체가 불가(잠김). */
    private String apiKey = "";
}
