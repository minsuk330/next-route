package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>feed 가 실제로 내려주는 전 노선</b>으로 방향 해석을 검증한다.
 *
 * <p>고른 노선 몇 개로 통과하는 시험은 이 문제에 소용이 없다. 방향 표기는 feed 가 정하고 우리는
 * 읽기만 하는데, <b>표기 방식이 노선마다 다르다</b> — 꺾쇠로 덧붙이는 도시철도와 이름 자리에
 * 방향만 적는 철도가 한 feed 에 섞여 있다. 하나라도 못 읽으면 그 노선만 <b>조용히</b> 방향 없이
 * 나가고, 사용자는 반대 방향 열차를 본다.
 *
 * <p>fixture 는 VPS OTP 에서 그대로 받아 온 61개 노선이다(2026-09-20). feed 가 갱신돼 표기가
 * 바뀌면 이 시험이 먼저 깨지는 것이 목적이다 — 앱이 깨지기 전에.
 */
@DisplayName("GTFS 전 노선 방향 커버리지")
class GtfsRailDirectionCoverageTest {

    /**
     * 방향이 없는 것이 <b>맞는</b> 노선.
     *
     * <p>서울6호선은 응암 순환을 포함해 feed 에 방향 구분 없이 하나로만 들어 있다. 여기 적어 둔
     * 이유는 "못 읽었다"와 "원래 없다"를 가르기 위해서다 — 목록 밖의 노선이 방향을 잃으면 그건
     * 사고다.
     */
    private static final Set<String> DIRECTIONLESS = Set.of("1:RR_ACC1_S-1-06-00");

    private final ObjectMapper om = new ObjectMapper();

    @Test
    @DisplayName("모든 노선의 방향을 읽어 wayCode 로 옮긴다 — 알려진 예외만 비어 있다")
    void everyRouteDirectionResolves() throws IOException {
        List<String> unreadable = new ArrayList<>();
        List<String> unmapped = new ArrayList<>();

        for (JsonNode route : routes()) {
            String gtfsId = route.path("gtfsId").asText();
            String label = SubwayDirectionNormalizer.labelFrom(route.path("longName").asText(null));

            if (DIRECTIONLESS.contains(gtfsId)) {
                assertThat(label)
                        .as("방향이 없다고 알려진 노선인데 값이 읽혔다 — 목록을 고칠 것: %s", gtfsId)
                        .isNull();
                continue;
            }
            if (label == null) {
                unreadable.add(gtfsId + " | " + route.path("longName").asText());
                continue;
            }
            if (SubwayDirectionNormalizer.toWayCode(label) == null) {
                unmapped.add(gtfsId + " | " + label);
            }
        }

        assertThat(unreadable)
                .as("방향을 못 읽은 노선. 이 노선들은 실시간 도착이 양방향으로 나간다")
                .isEmpty();
        assertThat(unmapped)
                .as("라벨은 읽었는데 wayCode 로 못 옮긴 노선. 값 공간이 늘었다는 뜻이다")
                .isEmpty();
    }

    @Test
    @DisplayName("읽은 방향은 그 노선의 값 공간으로 정확히 되돌아온다 — 순환선은 내·외선")
    void everyDirectionRoundTripsWithinItsLine() throws IOException {
        for (JsonNode route : routes()) {
            String label = SubwayDirectionNormalizer.labelFrom(route.path("longName").asText(null));
            if (label == null) {
                continue;
            }
            Integer wayCode = SubwayDirectionNormalizer.toWayCode(label);
            // 순환선(내·외선)인지는 라벨이 말한다. 노선 번호로 가르면 "인천2호선"이 2호선이 된다.
            String lineId = isCircular(label) ? "1002" : "1001";

            assertThat(SubwayDirectionNormalizer.fromWayCode(lineId, wayCode))
                    .as("%s 의 방향이 왕복에서 변했다", route.path("gtfsId").asText())
                    .isEqualTo(label);
        }
    }

    @Test
    @DisplayName("방향을 쓰는 노선은 양방향이 다 있다 — 한쪽만 읽히면 절반은 영원히 빈손이다")
    void everyDirectionalLineHasBothWays() throws IOException {
        Map<String, Set<String>> byLine = new LinkedHashMap<>();
        for (JsonNode route : routes()) {
            String label = SubwayDirectionNormalizer.labelFrom(route.path("longName").asText(null));
            if (label == null) {
                continue;
            }
            byLine.computeIfAbsent(route.path("shortName").asText(), key -> new LinkedHashSet<>())
                    .add(label);
        }

        assertThat(byLine).isNotEmpty();
        byLine.forEach((line, labels) -> assertThat(labels)
                .as("%s 의 방향이 한쪽만 읽혔다", line)
                .hasSize(2));
    }

    private boolean isCircular(String label) {
        return "내선".equals(label) || "외선".equals(label);
    }

    private JsonNode routes() throws IOException {
        JsonNode root = om.readTree(
                new ClassPathResource("otp/gtfs-rail-routes.json").getContentAsByteArray());
        JsonNode routes = root.path("routes");
        assertThat(routes).as("fixture 가 비었다").isNotEmpty();
        return routes;
    }
}
