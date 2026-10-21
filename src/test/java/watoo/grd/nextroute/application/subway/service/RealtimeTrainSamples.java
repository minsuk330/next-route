package watoo.grd.nextroute.application.subway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** 운영 실시간 열차 표본({@code subway/express/realtime-train-samples.json}, 2026-10-21 읽기 전용 실측). */
final class RealtimeTrainSamples {

    record Sample(String source, String lineId, String trainNo, String direction, String destination,
                  String trainType, String directAt, String currentStationId, String expectedCatalogNo,
                  String expectedKind, String expectedService, String note) {

        @Override
        public String toString() {
            return source + " " + lineId + " " + trainNo + " " + direction + " → " + expectedKind
                    + (expectedService == null ? "" : " " + expectedService);
        }
    }

    static final List<Sample> ALL = load();

    private RealtimeTrainSamples() {
    }

    private static List<Sample> load() {
        try {
            JsonNode root = new ObjectMapper().readTree(
                    new ClassPathResource("subway/express/realtime-train-samples.json").getContentAsByteArray());
            List<Sample> out = new ArrayList<>();
            for (JsonNode s : root.path("samples")) {
                out.add(new Sample(text(s, "source"), text(s, "lineId"), text(s, "trainNo"), text(s, "direction"),
                        text(s, "destination"), text(s, "trainType"), text(s, "directAt"), text(s, "currentStationId"),
                        text(s, "expectedCatalogNo"), text(s, "expectedKind"), text(s, "expectedService"),
                        text(s, "note")));
            }
            return List.copyOf(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
