package watoo.grd.nextroute.application.journey.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.journey.service.BusVehicleMatcher.Departure;
import watoo.grd.nextroute.application.journey.service.BusVehicleMatcher.Kind;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("버스 차량 시각 대조")
class BusVehicleMatcherTest {

    private static final Instant T0 = Instant.parse("2026-10-07T03:00:00Z");
    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final Duration GAP = Duration.ofSeconds(45);

    @Test
    @DisplayName("창 안 1대 → 그 차")
    void single() {
        var result = BusVehicleMatcher.match(T0, List.of(dep("A", 20)), WINDOW, GAP);

        assertThat(result.kind()).isEqualTo(Kind.MATCHED);
        assertThat(result.departure().vehicleId()).isEqualTo("A");
    }

    @Test
    @DisplayName("0대 / 창 밖만 → 없음")
    void none() {
        assertThat(BusVehicleMatcher.match(T0, List.of(), WINDOW, GAP).kind()).isEqualTo(Kind.NONE);
        assertThat(BusVehicleMatcher.match(T0, List.of(dep("A", 90), dep("B", -75)), WINDOW, GAP).kind())
                .isEqualTo(Kind.NONE);
    }

    @Test
    @DisplayName("2대가 갈리면(45초 이상) 가까운 차")
    void twoSeparated() {
        var result = BusVehicleMatcher.match(T0, List.of(dep("A", 5), dep("B", 55)), WINDOW, GAP);

        assertThat(result.kind()).isEqualTo(Kind.MATCHED);
        assertThat(result.departure().vehicleId()).isEqualTo("A");
    }

    @Test
    @DisplayName("2대가 붙어 오면 고르지 않는다")
    void twoBunched() {
        assertThat(BusVehicleMatcher.match(T0, List.of(dep("A", 5), dep("B", 25)), WINDOW, GAP).kind())
                .isEqualTo(Kind.AMBIGUOUS);
    }

    @Test
    @DisplayName("같은 차가 두 번 잡혀도 한 대다")
    void duplicateVehicle() {
        var result = BusVehicleMatcher.match(T0, List.of(dep("A", 10), dep("A", 40)), WINDOW, GAP);

        assertThat(result.kind()).isEqualTo(Kind.MATCHED);
        assertThat(result.departure().departedAt()).isEqualTo(T0.plusSeconds(10));
    }

    private static Departure dep(String vehicle, int offsetSec) {
        return new Departure(vehicle, "plate-" + vehicle, T0.plusSeconds(offsetSec));
    }
}
