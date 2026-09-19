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
        assertThat(bus.geometry().quality()).isEqualTo(GeometryQuality.STREET_FALLBACK);
        assertThat(bus.missing())
                .contains(MissingAttribute.REALTIME_ID, MissingAttribute.DIRECTION, MissingAttribute.COLOR);

        RoutingLeg walk = it.legs().get(0);
        assertThat(walk.walkSteps()).isNotEmpty();          // OTP steps(TMAP 대체)
        assertThat(walk.stops()).isEmpty();

        // fare 없음 → UNKNOWN(0 생성 금지), 형상 fallback → 네비 미승인
        assertThat(it.fare().availability()).isEqualTo(FareAvailability.UNKNOWN);
        assertThat(it.navigationSupported()).isFalse();
        assertThat(it.navigationBlockedReasons()).contains("GEOMETRY_STREET_FALLBACK");
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
