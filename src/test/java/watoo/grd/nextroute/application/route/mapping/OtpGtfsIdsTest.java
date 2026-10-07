package watoo.grd.nextroute.application.route.mapping;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("서울 버스 id → OTP GTFS id")
class OtpGtfsIdsTest {

    @Test
    @DisplayName("9자리 숫자만 바꾸고, 그 밖은 null")
    void rule() {
        assertThat(OtpGtfsIds.seoulBusStop("121000091")).isEqualTo("1:BS_TAGO_SEB121000091");
        assertThat(OtpGtfsIds.seoulBusRoute("100100093")).isEqualTo("1:BR_TAGO_SEB100100093");
        assertThat(OtpGtfsIds.seoulBusStop("1001")).isNull();
        assertThat(OtpGtfsIds.seoulBusStop("GGB200000001")).isNull();
        assertThat(OtpGtfsIds.seoulBusRoute(null)).isNull();
    }

    @Test
    @DisplayName("기존 역방향 규칙(OTP → 서울)과 왕복한다")
    void roundTripsWithResolverRule() {
        String stop = OtpGtfsIds.seoulBusStop("121000091");
        assertThat(stop.replaceFirst("^\\d+:BS_TAGO_SEB", "")).isEqualTo("121000091");
    }
}
