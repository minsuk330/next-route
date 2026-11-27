package watoo.grd.nextroute.common.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * 이 jar 가 빌드된 커밋을 {@code app_build{revision, dirty}} 게이지(값 1)로 알린다.
 *
 * <p>장애 분석기(alert-analyzer)가 장애 난 role 의 커밋을 Prometheus 에서 읽고, GitHub 에서 그 커밋의 코드를
 * 받아 스택이 가리킨 메서드를 분석에 넣는다(docs/plan/rag-incident-context). 운영 버전과 main 이 다를 수 있어서
 * main 이 아니라 이 값을 쓴다. role·instance 라벨은 Prometheus 수집 설정이 붙인다.
 *
 * <p>커밋은 빌드 때 build.gradle 이 {@code META-INF/build-info.properties} 에 굽는다. 40자 SHA 가 아니면
 * (git 없는 빌드, build-info 없는 IDE·테스트 실행) {@code unknown} — 분석기는 그때 코드를 가져오지 않는다.
 *
 * <p>이름이 {@code app.build.info} 가 아닌 이유: Prometheus 클라이언트가 {@code _info} 접미사를 Info 타입으로
 * 바꿔 노출 이름이 달라진다.
 */
@Component
public class BuildInfoMetrics implements MeterBinder {

    static final String UNKNOWN = "unknown";
    private static final Pattern FULL_SHA = Pattern.compile("[0-9a-f]{40}");

    private final String revision;
    private final String dirty;

    public BuildInfoMetrics(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties props = buildProperties.getIfAvailable();
        this.revision = revision(props == null ? null : props.get("revision"));
        this.dirty = "true".equals(props == null ? null : props.get("dirty")) ? "true" : "false";
    }

    static String revision(String raw) {
        return raw != null && FULL_SHA.matcher(raw).matches() ? raw : UNKNOWN;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("app.build", () -> 1)
                .description("빌드 커밋. 값은 늘 1")
                .tag("revision", revision)
                .tag("dirty", dirty)
                .register(registry);
    }
}
