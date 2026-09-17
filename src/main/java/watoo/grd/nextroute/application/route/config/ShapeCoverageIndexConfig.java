package watoo.grd.nextroute.application.route.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import watoo.grd.nextroute.application.route.routing.ShapeCoverageIndex;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

/**
 * coverage-index.json → {@link ShapeCoverageIndex} 빈. 그래프와 함께 배포되는 파일을 읽는다.
 *
 * <p>부재/파싱실패는 예외로 죽지 않고 {@link ShapeCoverageIndex#disabled()} 로 폴백한다(형상 전량
 * STREET_FALLBACK = 현행 동작). shadow/dormant 배포에서 파일이 아직 없어도 부팅이 깨지지 않는다.
 */
@Slf4j
@Configuration
public class ShapeCoverageIndexConfig {

    @Bean
    public ShapeCoverageIndex shapeCoverageIndex(OtpRoutingProperties props,
                                                 ObjectMapper objectMapper,
                                                 ResourceLoader resourceLoader) {
        String path = props.getCoverageIndexPath();
        if (path == null || path.isBlank()) {
            return ShapeCoverageIndex.disabled();
        }
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            log.info("coverage-index 없음({}) → 형상 마킹 비활성", path);
            return ShapeCoverageIndex.disabled();
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = objectMapper.readTree(in);
            String graphVersion = root.path("graphVersion").asText("");
            Set<String> coveredModes = readStrings(root.path("coveredModes"));
            Set<String> degraded = readStrings(root.path("degradedTripIds"));
            log.info("coverage-index 로드: graphVersion={} modes={} degraded={}",
                    graphVersion, coveredModes, degraded.size());
            return new ShapeCoverageIndex(graphVersion, coveredModes, degraded);
        } catch (Exception e) {
            log.warn("coverage-index 로드 실패({}) → 형상 마킹 비활성: {}", path, e.toString());
            return ShapeCoverageIndex.disabled();
        }
    }

    private static Set<String> readStrings(JsonNode array) {
        Set<String> out = new HashSet<>();
        if (array.isArray()) {
            for (JsonNode n : array) {
                out.add(n.asText());
            }
        }
        return out;
    }
}
