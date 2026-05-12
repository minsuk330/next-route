package watoo.grd.nextroute.application.route.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "tmap")
public class TmapProperties {
    private String appKey;
    private String baseUrl;
    private int timeoutMs = 1500;
    private boolean retryOnFailure = true;
}
