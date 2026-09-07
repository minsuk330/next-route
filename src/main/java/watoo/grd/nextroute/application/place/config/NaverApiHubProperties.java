package watoo.grd.nextroute.application.place.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "naver.api-hub")
public class NaverApiHubProperties {

    private String baseUrl = "https://naverapihub.apigw.ntruss.com";
    private String clientId = "";
    private String clientSecret = "";
}
