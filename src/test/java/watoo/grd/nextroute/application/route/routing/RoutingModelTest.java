package watoo.grd.nextroute.application.route.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** PR1a 중립 모델 계약 테스트. 아무 호출부도 없으므로 기존 회귀 영향 0. */
class RoutingModelTest {

    private final Instant now = Instant.parse("2026-09-08T08:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);

    @Test
    @DisplayName("Fare: KNOWN 은 amount 필수, KNOWN_ZERO=0, UNKNOWN=null (0 을 unknown 으로 쓰지 않음)")
    void fareInvariants() {
        assertThat(Fare.known(1250).amount()).isEqualTo(1250);
        assertThat(Fare.free().availability()).isEqualTo(FareAvailability.KNOWN_ZERO);
        assertThat(Fare.free().amount()).isZero();
        assertThat(Fare.unknown().amount()).isNull();
        assertThatThrownBy(() -> new Fare(FareAvailability.KNOWN, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("RoutingOutcome: 성공/실패 sealed 구분, OUT_OF_SCOPE 는 별도 kind")
    void outcomeSealed() {
        RoutingOutcome ok = new RoutingResult(RoutingProvider.OTP, "v2", "net1", "g1",
                null, null, now, List.of(), List.of());
        RoutingOutcome fail = RoutingFailure.of(RoutingFailure.Kind.OUT_OF_SCOPE, "OUTSIDE_BOUNDS", "bbox 밖");
        assertThat(ok.success()).isTrue();
        assertThat(fail.success()).isFalse();
        assertThat(((RoutingFailure) fail).kind()).isEqualTo(RoutingFailure.Kind.OUT_OF_SCOPE);
        assertThat(ok).isInstanceOf(RoutingResult.class);
        assertThat(((RoutingResult) ok).provider()).isEqualTo(RoutingProvider.OTP);
    }

    @Test
    @DisplayName("ODsay 는 graphVersion sentinel, OTP 버전과 혼용 안 함")
    void odsaySentinel() {
        RoutingResult odsay = new RoutingResult(RoutingProvider.ODSAY, "v2", "net1",
                RoutingResult.ODSAY_NO_GRAPH, null, null, now, List.of(), List.of());
        assertThat(odsay.graphVersion()).isEqualTo("ODSAY_NO_GRAPH");
    }

    @Test
    @DisplayName("RoutingQuery: departureAt/provider 확정 강제")
    void queryValidation() {
        assertThatThrownBy(() -> new RoutingQuery(126.9, 37.5, "o", 127.0, 37.4, "d",
                null, Set.of(), null, false, "v2", RoutingProvider.OTP, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RoutingQuery(126.9, 37.5, "o", 127.0, 37.4, "d",
                now, Set.of(), null, false, "v2", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("컬렉션 필드는 null-안전 + 방어복사(불변)")
    void defensiveCopy() {
        RoutingLeg leg = new RoutingLeg(RoutingMode.BUS, null, "1", "1:A1", "1:R", "143",
                null, null, null, null, 600, null, null,
                LegGeometry.missing("no shape"), Enrichment.staticOnly(RealtimeCoverage.STATIC_SCHEDULE), null);
        assertThat(leg.stops()).isEmpty();
        assertThat(leg.missing()).isEmpty();
        assertThatThrownBy(() -> leg.stops().add(
                new StopOccurrence("s", null, null, 0, 0, 0, "n")))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(leg.isTransit()).isTrue();

        RoutingItinerary it = new RoutingItinerary(now, 600, 120, 300, 1, false, null, null, null);
        assertThat(it.navigationBlockedReasons()).isEmpty();
        assertThat(it.fare().availability()).isEqualTo(FareAvailability.UNKNOWN);
    }

    @Test
    @DisplayName("RoutingDeadline: 주입 Clock 으로 남은 예산/만료 판정")
    void deadline() {
        RoutingDeadline d = RoutingDeadline.of(clock, 2000);
        assertThat(d.remainingMillis(clock)).isEqualTo(2000);
        assertThat(d.expired(clock)).isFalse();
        Clock later = Clock.fixed(now.plusMillis(2500), ZoneOffset.UTC);
        assertThat(d.remainingMillis(later)).isZero();
        assertThat(d.expired(later)).isTrue();
    }

    @Test
    @DisplayName("RoutingResult JSON 직렬화(계약이 Jackson 으로 왕복)")
    void jsonRoundTrip() throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        RoutingResult r = new RoutingResult(RoutingProvider.OTP, "v2", "net1", "g1", "m1", "geo1",
                now, List.of("w"), List.of());
        String json = om.writeValueAsString(r);
        RoutingResult back = om.readValue(json, RoutingResult.class);
        assertThat(back.provider()).isEqualTo(RoutingProvider.OTP);
        assertThat(back.geometryVersion()).isEqualTo("geo1");
        assertThat(back.warnings()).containsExactly("w");
    }
}
