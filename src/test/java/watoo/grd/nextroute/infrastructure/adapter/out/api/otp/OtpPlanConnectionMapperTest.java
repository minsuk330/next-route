package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import watoo.grd.nextroute.application.route.routing.FareAvailability;
import watoo.grd.nextroute.application.route.routing.GeometryQuality;
import watoo.grd.nextroute.application.route.routing.MissingAttribute;
import watoo.grd.nextroute.application.route.routing.RoutingFailure;
import watoo.grd.nextroute.application.route.routing.RoutingItinerary;
import watoo.grd.nextroute.application.route.routing.RoutingLeg;
import watoo.grd.nextroute.application.route.routing.RoutingMode;
import watoo.grd.nextroute.application.route.routing.RoutingOutcome;
import watoo.grd.nextroute.application.route.routing.RoutingResult;
import watoo.grd.nextroute.application.route.routing.StopOccurrence;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 OTP 2.9.0 planConnection 응답(fixture)으로 mapper 계약을 고정한다. */
class OtpPlanConnectionMapperTest {

    private final ObjectMapper om = new ObjectMapper();
    private final OtpPlanConnectionMapper mapper = new OtpPlanConnectionMapper();
    private final Instant departureAt = Instant.parse("2026-09-07T23:00:00Z");
    private final OtpPlanConnectionMapper.OtpVersions versions =
            new OtpPlanConnectionMapper.OtpVersions("schema-v1", "202503", "g-abc");

    private JsonNode load(String path) throws IOException {
        return om.readTree(new ClassPathResource(path).getContentAsByteArray());
    }

    @Test
    @DisplayName("노선 이름의 꺾쇠가 방향이다 — 읽어 내면 DIRECTION 이 더 이상 '없는 속성'이 아니다")
    void readsDirectionFromRouteLongName() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-subway.json"), departureAt, versions);

        RoutingLeg subway = ((RoutingResult) outcome).itineraries().get(0).legs().get(0);
        assertThat(subway.mode()).isEqualTo(RoutingMode.SUBWAY);
        // longName = "서울1호선(의정부-서동탄)<상행>"
        assertThat(subway.sourceRouteDirection()).isEqualTo("상행");
        assertThat(subway.missing()).doesNotContain(MissingAttribute.DIRECTION);
    }

    @Test
    @DisplayName("버스 구간의 nextLegs 를 대체 노선 원재료로 옮긴다 — 거르지 않는다(otp-bus-lane-merge)")
    void mapsNextLegsToBusAlternatives() throws IOException {
        RoutingItinerary it = ((RoutingResult) mapper.map(load("otp/planconnection-bus-alternatives.json"),
                departureAt, versions)).itineraries().get(0);
        RoutingLeg bus = it.legs().stream().filter(l -> l.mode() == RoutingMode.BUS).findFirst().orElseThrow();
        RoutingLeg subway = it.legs().stream().filter(l -> l.mode() == RoutingMode.SUBWAY).findFirst().orElseThrow();

        assertThat(bus.sourceRouteName()).isEqualTo("8100");
        assertThat(bus.alternatives()).extracting(a -> a.routeName())
                .containsExactly("5000A", "9200", "4103", "3201", "1101", "5005(예약)");
        assertThat(bus.alternatives().get(0).alightStopId()).isEqualTo("1:BS_TAGO_GGB101000148");
        assertThat(bus.alternatives().get(0).boardAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalTime()
                .withSecond(0)).isEqualTo(java.time.LocalTime.of(8, 48));
        assertThat(bus.alternatives().get(0).rideSeconds()).isPositive();
        // 이 fixture 의 nextLegs 는 stopCalls 를 묻기 전 응답이다 → 정류장 목록은 모름(빈 목록, 묶이지 않는다)
        assertThat(bus.alternatives().get(0).stopIds()).isEmpty();
        // stopCalls 가 온 편은 서는 정류장 id 를 순서대로 싣는다 — 9200 은 대표와 같고 4103 은 중간 정류장이 다르다
        assertThat(bus.alternatives().get(1).stopIds()).isEqualTo(bus.stops().stream().map(s -> s.sourceStopId()).toList());
        assertThat(bus.alternatives().get(2).stopIds()).hasSize(3).contains("1:BS_OTHER");
        // 지하철 구간에 실린 nextLegs 는 버리지 않고 무시한다(버스만 합친다)
        assertThat(subway.alternatives()).isEmpty();
    }

    @Test
    @DisplayName("nextLegs 를 묻지 않은 옛 응답이면 대체 노선은 빈 목록")
    void noNextLegsMeansEmptyAlternatives() throws IOException {
        RoutingResult r = (RoutingResult) mapper.map(load("otp/planconnection-success.json"), departureAt, versions);

        assertThat(r.itineraries()).allSatisfy(i -> assertThat(i.legs())
                .allSatisfy(l -> assertThat(l.alternatives()).isEmpty()));
    }

    @Test
    @DisplayName("노선 긴 이름·trip 패턴 코드를 보존하고 열차 종류는 판정하지 않는다(subway-express S2 — 판정은 어댑터 경계)")
    void keepsLongNameAndPatternCodeWithoutClassifying() throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) load("otp/planconnection-subway.json");
        com.fasterxml.jackson.databind.node.ObjectNode trip = (com.fasterxml.jackson.databind.node.ObjectNode)
                root.path("data").path("planConnection").path("edges").get(0).path("node").path("legs").get(0)
                        .path("trip");
        trip.putObject("pattern").put("code", "1:RR_ACC1_S-1-01-1U::03");

        RoutingLeg subway = ((RoutingResult) mapper.map(root, departureAt, versions)).itineraries().get(0).legs().get(0);

        assertThat(subway.sourceRouteLongName()).isEqualTo("서울1호선(의정부-서동탄)<상행>");
        assertThat(subway.patternCode()).isEqualTo("1:RR_ACC1_S-1-01-1U::03");
        assertThat(subway.trainKind()).isNull();
        // 같은 구간을 enrichment 만 바꿔 복사해도 새 칸이 살아 있다
        assertThat(subway.withEnrichment(subway.enrichment()).patternCode()).isEqualTo("1:RR_ACC1_S-1-01-1U::03");
    }

    @Test
    @DisplayName("trip 에 pattern 이 없는 옛 응답이면 패턴 코드는 null")
    void patternCodeMissing() throws IOException {
        RoutingLeg subway = ((RoutingResult) mapper.map(load("otp/planconnection-subway.json"), departureAt, versions))
                .itineraries().get(0).legs().get(0);

        assertThat(subway.patternCode()).isNull();
        assertThat(subway.sourceRouteLongName()).isEqualTo("서울1호선(의정부-서동탄)<상행>");
    }

    @Test
    @DisplayName("6호선은 노선 이름에 라벨이 없다 — 승·하차역 순서로 방향을 정한다(#395)")
    void line6DirectionFromStopOrder() throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) load("otp/planconnection-subway.json");
        JsonNode leg = root.path("data").path("planConnection").path("edges").get(0).path("node").path("legs").get(0);
        com.fasterxml.jackson.databind.node.ObjectNode route = (com.fasterxml.jackson.databind.node.ObjectNode) leg.path("route");
        route.put("gtfsId", "1:RR_ACC1_S-1-06-00");
        route.put("longName", "서울6호선");
        JsonNode calls = leg.path("stopCalls");
        ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(0).path("stopLocation"))
                .put("gtfsId", "1:RS_ACC1_S-1-2631");                       // 이태원
        ((com.fasterxml.jackson.databind.node.ObjectNode) calls.get(calls.size() - 1).path("stopLocation"))
                .put("gtfsId", "1:RS_ACC1_S-1-2626");                       // 대흥

        RoutingLeg subway = ((RoutingResult) mapper.map(root, departureAt, versions)).itineraries().get(0).legs().get(0);

        assertThat(subway.sourceRouteDirection()).isEqualTo("상행");
        assertThat(subway.missing()).doesNotContain(MissingAttribute.DIRECTION);
    }

    @Test
    @DisplayName("그래프가 준 route.color 를 leg 에 담는다 — 색이 있으면 COLOR 는 '없는 속성'이 아니다")
    void readsRouteColor() throws IOException {
        String json = new String(new ClassPathResource("otp/planconnection-subway.json").getContentAsByteArray(),
                java.nio.charset.StandardCharsets.UTF_8).replaceFirst("\"color\": null", "\"color\": \"004A85\"");
        RoutingOutcome outcome = mapper.map(om.readTree(json), departureAt, versions);

        RoutingLeg subway = ((RoutingResult) outcome).itineraries().get(0).legs().get(0);
        assertThat(subway.sourceRouteColor()).isEqualTo("004A85");
        assertThat(subway.missing()).doesNotContain(MissingAttribute.COLOR);
    }

    @Test
    @DisplayName("꺾쇠가 없으면 방향은 모르는 것이다 — 지어내지 않고 DIRECTION 을 남긴다")
    void keepsDirectionMissingWhenLongNameHasNoLabel() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-success.json"), departureAt, versions);

        RoutingLeg bus = ((RoutingResult) outcome).itineraries().get(0).legs().get(1);
        assertThat(bus.sourceRouteDirection()).isNull();
        assertThat(bus.missing()).contains(MissingAttribute.DIRECTION);
    }

    @Test
    @DisplayName("leg 거리(m)를 담는다 — 도보 거리·totalWalk 의 원천(#399)")
    void readsLegDistance() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-success.json"), departureAt, versions);

        RoutingItinerary it = ((RoutingResult) outcome).itineraries().get(0);
        assertThat(it.legs()).extracting(RoutingLeg::distanceMeters)
                .containsExactly(853.57, 7056.7, 111.41, 4735.33, 416.72);
    }

    @Test
    @DisplayName("엔진이 매긴 일반화비용을 담는다 — 후보 정렬 키다")
    void readsGeneralizedCost() throws IOException {
        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) load("otp/planconnection-success.json");
        ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("data").path("planConnection")
                .path("edges").get(0).path("node")).put("generalizedCost", 3557);

        RoutingOutcome outcome = mapper.map(root, departureAt, versions);

        assertThat(((RoutingResult) outcome).itineraries().get(0).generalizedCost()).isEqualTo(3557);
    }

    @Test
    @DisplayName("일반화비용이 없으면 null — 0 으로 지어내지 않는다")
    void missingGeneralizedCostIsNull() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-success.json"), departureAt, versions);

        assertThat(((RoutingResult) outcome).itineraries().get(0).generalizedCost()).isNull();
    }

    @Test
    @DisplayName("성공 응답 → RoutingResult: provider/버전/leg 매핑, 원천 ID·정차역 보존")
    void mapsSuccess() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-success.json"), departureAt, versions);

        assertThat(outcome.success()).isTrue();
        RoutingResult result = (RoutingResult) outcome;
        assertThat(result.graphVersion()).isEqualTo("g-abc");
        assertThat(result.departureAt()).isEqualTo(departureAt);
        assertThat(result.itineraries()).isNotEmpty();

        RoutingItinerary it = result.itineraries().get(0);
        assertThat(it.legs()).extracting(RoutingLeg::mode)
                .containsExactly(RoutingMode.WALK, RoutingMode.BUS, RoutingMode.WALK,
                        RoutingMode.BUS, RoutingMode.WALK);

        RoutingLeg bus = it.legs().get(1);
        assertThat(bus.sourceRouteId()).startsWith("1:BR_TAGO_");
        assertThat(bus.sourceRouteName()).isEqualTo("143");
        assertThat(bus.feedId()).isEqualTo("1");
        assertThat(bus.agencyId()).isEqualTo("1:A1");
        assertThat(bus.serviceDate()).isNotNull();
        assertThat(bus.stops()).isNotEmpty();              // from + stopCalls + to
        assertThat(bus.geometry().quality()).isEqualTo(GeometryQuality.SHAPE_OK);
        assertThat(bus.missing())
                .contains(MissingAttribute.REALTIME_ID, MissingAttribute.DIRECTION, MissingAttribute.COLOR);

        RoutingLeg walk = it.legs().get(0);
        assertThat(walk.walkSteps()).isNotEmpty();          // OTP steps(TMAP 대체)
        assertThat(walk.stops()).isEmpty();

        // fare 없음 → UNKNOWN(0 생성 금지). 형상은 shapes.txt 라 형상 사유로는 막지 않는다.
        assertThat(it.fare().availability()).isEqualTo(FareAvailability.UNKNOWN);
        assertThat(it.navigationSupported()).isFalse();
        assertThat(it.navigationBlockedReasons()).doesNotContain("GEOMETRY_STREET_FALLBACK");
    }

    @Test
    @DisplayName("stopCalls schedule → StopOccurrence 시간표 시각 채움, stopCalls 가 board·alight 포함이라 addPlace 중복 없음")
    void mapsStopCallTiming() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-stopcall-times.json"), departureAt, versions);

        assertThat(outcome.success()).isTrue();
        RoutingResult result = (RoutingResult) outcome;
        RoutingLeg bus = result.itineraries().get(0).legs().get(0);

        assertThat(bus.mode()).isEqualTo(RoutingMode.BUS);
        // stopCalls 자체가 board(occ0)·alight(occ8) 포함 9개 → addPlace(from)/addPlace(to) 중복 추가 없어야 함(9, 11 아님).
        assertThat(bus.stops()).hasSize(9);

        StopOccurrence board = bus.stops().get(0);
        assertThat(board.sourceStopId()).isEqualTo("1:BS_TAGO_SEB101000041");
        assertThat(board.occurrenceIndex()).isEqualTo(0);
        assertThat(board.scheduledDeparture()).isEqualTo(offset("2026-09-08T08:12:28+09:00"));

        StopOccurrence middle = bus.stops().get(1);
        assertThat(middle.scheduledArrival()).isEqualTo(offset("2026-09-08T08:14:10+09:00"));

        StopOccurrence alight = bus.stops().get(8);
        assertThat(alight.sourceStopId()).isEqualTo("1:BS_TAGO_SEB121000020");
        assertThat(alight.occurrenceIndex()).isEqualTo(8);
        assertThat(alight.scheduledArrival()).isEqualTo(offset("2026-09-08T08:28:04+09:00"));
    }

    private static Instant offset(String iso) {
        return OffsetDateTime.parse(iso).toInstant();
    }

    @Test
    @DisplayName("범위 밖 → RoutingFailure(OUT_OF_SCOPE), OTP OUTSIDE_BOUNDS 코드 보존")
    void mapsOutOfScope() throws IOException {
        RoutingOutcome outcome = mapper.map(load("otp/planconnection-out-of-scope.json"), departureAt, versions);

        assertThat(outcome.success()).isFalse();
        RoutingFailure failure = (RoutingFailure) outcome;
        assertThat(failure.kind()).isEqualTo(RoutingFailure.Kind.OUT_OF_SCOPE);
        assertThat(failure.code()).isEqualTo("OUTSIDE_BOUNDS");
    }
}
