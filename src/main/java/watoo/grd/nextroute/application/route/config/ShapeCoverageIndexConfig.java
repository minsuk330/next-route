package watoo.grd.nextroute.application.route.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import watoo.grd.nextroute.application.route.routing.ShapeCoverageIndex;
import watoo.grd.nextroute.application.route.routing.ShapeMarkingStatus;

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
        ShapeCoverageIndex index = load(props, objectMapper, resourceLoader);
        // 배포 원자성 게이트(PR-B2): 유효 상태를 기동 시 한 줄로 드러낸다. graphVersion 이 어긋난
        // 배포 조합은 조용히 fallback 되지 않고 WARN 으로 즉시 잡힌다.
        logMarkingStatus(props, index);
        return index;
    }

    private ShapeCoverageIndex load(OtpRoutingProperties props, ObjectMapper objectMapper,
                                    ResourceLoader resourceLoader) {
        String path = props.getCoverageIndexPath();
        if (path == null || path.isBlank()) {
            return ShapeCoverageIndex.disabled();
        }
        Resource resource = resourceLoader.getResource(path);
        if (!resource.exists()) {
            log.info("coverage-index 없음({})", path);
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
            log.warn("coverage-index 로드 실패({}): {}", path, e.toString());
            return ShapeCoverageIndex.disabled();
        }
    }

    private void logMarkingStatus(OtpRoutingProperties props, ShapeCoverageIndex index) {
        String cfg = props.getGraphVersion();
        ShapeMarkingStatus status = index.markingStatus(props.isShapesEnabled(), cfg);
        switch (status) {
            case ACTIVE -> log.info(
                    "형상 마킹 ACTIVE: graphVersion={} coveredModes={} degraded={}",
                    index.graphVersion(), index.coveredModes(), index.degradedCount());
            case DISABLED -> log.info("형상 마킹 OFF (shapesEnabled=false) → 전량 STREET_FALLBACK");
            case NO_INDEX -> log.warn(
                    "형상 마킹 ON 이나 coverage-index 없음/로드실패 → 전량 STREET_FALLBACK "
                    + "(coverageIndexPath={})", props.getCoverageIndexPath());
            case GRAPH_VERSION_MISMATCH -> log.warn(
                    "형상 마킹 ON 이나 graphVersion 불일치 → 전량 STREET_FALLBACK. "
                    + "index={} config={} (배포 번들이 어긋남 — graph/coverage-index/graphVersion 한 세트로 교체 필요)",
                    index.graphVersion(), cfg);
            default -> { /* exhaustive */ }
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
