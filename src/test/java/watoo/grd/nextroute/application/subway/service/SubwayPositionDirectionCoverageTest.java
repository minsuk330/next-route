package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위치 API 방향 해석을 <b>운영 원문 전 노선</b>으로 검증한다(2026-10-22 방향 감사).
 *
 * <p>fixture 는 같은 회차에 받은 위치 API 행과 도착 API 행을 열차번호로 맞춘 것이다. 도착 API 의 {@code updnLine} 이 정답이다 —
 * 역 도착 목록·노선 패턴·검색 방향이 모두 그 표기를 쓴다. 9호선·공항철도(노선 전체)와 2호선 성수지선(구간)이 위치 API 에서 반대로
 * 오는 것을 여기서 함께 지킨다. 노선이 늘거나 운영사가 표기를 바꾸면 이 시험이 먼저 깨진다.
 */
@DisplayName("위치 API 방향 전 노선 대조")
class SubwayPositionDirectionCoverageTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @Test
    @DisplayName("위치 행의 방향(정규화 후)이 같은 열차의 도착 API 방향과 같다 — 전 노선")
    void positionDirectionMatchesArrivalForEveryLine() throws IOException {
        JsonNode rows = OM.readTree(new ClassPathResource("subway/direction/position-vs-arrival.json")
                .getContentAsByteArray()).path("rows");
        List<String> mismatches = new ArrayList<>();
        Map<String, Integer> perLine = new TreeMap<>();
        for (JsonNode r : rows) {
            String lineId = r.path("lineId").asText();
            String ours = SubwayDirectionNormalizer.fromPositionRow(lineId, r.path("updnLine").asText(),
                    r.path("statnId").asText(), r.path("statnTid").asText(), r.path("statnTnm").asText());
            String official = r.path("arrivalUpdnLine").asText();
            perLine.merge(lineId, 1, Integer::sum);
            if (!official.equals(ours)) {
                mismatches.add(lineId + " " + r.path("trainNo").asText() + " updnLine=" + r.path("updnLine").asText()
                        + " @" + r.path("statnId").asText() + " → " + ours + " (도착 API " + official + ")");
            }
        }
        assertThat(mismatches).as("도착 API 와 다른 위치 방향").isEmpty();
        assertThat(perLine.keySet()).as("대조한 노선").contains(
                "1001", "1002", "1003", "1004", "1005", "1006", "1007", "1008", "1009",
                "1032", "1063", "1065", "1067", "1075", "1077", "1092");
        long branchRows = rows.findValues("statnId").stream()
                .filter(id -> SubwayDirectionNormalizer.SEONGSU_BRANCH_STATIONS.contains(id.asText())).count();
        assertThat(branchRows).as("성수지선 열차 행이 fixture 에 있어야 반전을 지킨다").isPositive();
    }
}
