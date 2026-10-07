package watoo.grd.nextroute.infrastructure.adapter.out.api.otp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import watoo.grd.nextroute.application.subway.dto.OtpRailRoute;
import watoo.grd.nextroute.application.subway.dto.RailPattern;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OtpSubwayRoutePatternAdapterTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void parsesRoutes() throws Exception {
        List<OtpRailRoute> routes = OtpSubwayRoutePatternAdapter.parseRoutes(om.readTree("""
                {"data":{"routes":[{"gtfsId":"1:RR_ACC1_S-1-02-1I","shortName":"서울2호선","longName":"서울2호선(본선)<내선>"}]}}
                """));

        assertThat(routes).containsExactly(
                new OtpRailRoute("1:RR_ACC1_S-1-02-1I", "서울2호선", "서울2호선(본선)<내선>"));
    }

    @Test
    @DisplayName("trip 수와 첫 trip 시각을 읽고, 타지도 내리지도 않는 정차는 뺀다")
    void parsesPatternsWithFirstTripTimes() throws Exception {
        List<RailPattern> patterns = OtpSubwayRoutePatternAdapter.parsePatterns("R", om.readTree("""
                {"patterns":[{
                  "stops":[{"name":"신도림(2호선)","lat":37.5,"lon":126.8},
                           {"name":"통과역","lat":37.51,"lon":126.81},
                           {"name":"문래","lat":37.52,"lon":126.82,"gtfsId":"1:RS_ACC1_S-1-0235"}],
                  "trips":[{"stoptimes":[
                      {"scheduledArrival":19800,"scheduledDeparture":19800,"dropoffType":"NONE"},
                      {"scheduledArrival":19860,"scheduledDeparture":19860,"pickupType":"NONE","dropoffType":"NONE"},
                      {"scheduledArrival":19920,"scheduledDeparture":19950}]},
                    {},{}]
                }]}
                """));

        RailPattern pattern = patterns.get(0);
        assertThat(pattern.tripCount()).isEqualTo(3);
        assertThat(pattern.stops()).extracting(RailPattern.Stop::name).containsExactly("신도림(2호선)", "문래");
        assertThat(pattern.stops().get(1).arrivalSec()).isEqualTo(19920);
        assertThat(pattern.stops().get(1).departureSec()).isEqualTo(19950);
        // #298: GTFS 정류장 id 를 읽는다. 없으면(옛 응답) null
        assertThat(pattern.stops().get(1).gtfsId()).isEqualTo("1:RS_ACC1_S-1-0235");
        assertThat(pattern.stops().get(0).gtfsId()).isNull();
    }

    @Test
    @DisplayName("시각 수가 정차 수와 다르면 시각을 비운다 — 엉뚱한 역에 시각을 붙이지 않는다")
    void dropsTimesWhenMisaligned() throws Exception {
        List<RailPattern> patterns = OtpSubwayRoutePatternAdapter.parsePatterns("R", om.readTree("""
                {"patterns":[{"stops":[{"name":"A","lat":1,"lon":1},{"name":"B","lat":1,"lon":1}],
                  "trips":[{"stoptimes":[{"scheduledArrival":1,"scheduledDeparture":1}]}]}]}
                """));

        assertThat(patterns.get(0).stops()).extracting(RailPattern.Stop::arrivalSec).containsOnlyNulls();
    }
}
