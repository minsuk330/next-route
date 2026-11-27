package watoo.grd.nextroute.common.observability;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** 장애 분석기가 읽는 계약: Prometheus 노출 이름 app_build, 라벨 revision(40자 SHA 또는 unknown)·dirty. */
@DisplayName("빌드 커밋 메트릭")
class BuildInfoMetricsTest {

    private static final String SHA = "47d81acf4cdf9eefcf1dc3a0324f8f3947f800d0";

    private static String scrape(BuildProperties props) {
        StaticListableBeanFactory factory = new StaticListableBeanFactory();
        if (props != null) {
            factory.addBean("buildProperties", props);
        }
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        new BuildInfoMetrics(factory.getBeanProvider(BuildProperties.class)).bindTo(registry);
        return registry.scrape();
    }

    private static BuildProperties props(String revision, String dirty) {
        Properties p = new Properties();
        if (revision != null) p.setProperty("revision", revision);
        if (dirty != null) p.setProperty("dirty", dirty);
        return new BuildProperties(p);
    }

    @Test
    @DisplayName("40자 커밋이 app_build{revision} 로 노출된다")
    void exposesFullRevision() {
        assertThat(scrape(props(SHA, "false")))
                .contains("app_build{dirty=\"false\",revision=\"" + SHA + "\"} 1");
    }

    @Test
    @DisplayName("dirty 빌드는 dirty=true")
    void dirtyBuild() {
        assertThat(scrape(props(SHA, "true"))).contains("app_build{dirty=\"true\",revision=\"" + SHA + "\"} 1");
    }

    @Test
    @DisplayName("build-info 가 없으면 unknown")
    void noBuildInfo() {
        assertThat(scrape(null)).contains("app_build{dirty=\"false\",revision=\"unknown\"} 1");
    }

    @Test
    @DisplayName("짧은 SHA·대문자·이상한 값은 unknown — 분석기가 fetch 를 시도하지 않게")
    void rejectsNonFullSha() {
        assertThat(BuildInfoMetrics.revision("47d81acf")).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.revision(SHA.toUpperCase())).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.revision("manual")).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.revision(SHA + "\n")).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.revision(null)).isEqualTo("unknown");
        assertThat(BuildInfoMetrics.revision(SHA)).isEqualTo(SHA);
    }
}
