package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import watoo.grd.nextroute.application.route.config.OtpRoutingProperties;
import watoo.grd.nextroute.application.route.config.ShapeCoverageIndexConfig;
import watoo.grd.nextroute.application.route.routing.ShapeCoverageIndex;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DB 없이 형상 마킹 빈 배선만 검증한다(3-생성자 @Autowired 함정 + coverage-index 부재 폴백).
 * 전체 {@code @SpringBootTest} 는 localhost:5432(Flyway) 를 요구해 로컬에선 별개로 실패한다.
 */
class OtpShapeWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ObjectMapper.class)
            .withUserConfiguration(
                    OtpRoutingProperties.class,
                    ShapeCoverageIndexConfig.class,
                    OtpPlanConnectionMapper.class);

    @Test
    void wiresMapperAndDisabledIndexWhenCoverageFileAbsent() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(OtpPlanConnectionMapper.class);
            assertThat(ctx).hasSingleBean(ShapeCoverageIndex.class);
            // 기본 classpath coverage-index.json 부재 → disabled 폴백(부팅 안 깨짐, 형상 off).
            assertThat(ctx.getBean(ShapeCoverageIndex.class).graphVersion()).isEmpty();
        });
    }
}
